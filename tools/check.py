#!/usr/bin/env python3
"""
Checker for the case intake exercise. Standard library only. Python 3.9+.

    python3 tools/check.py --catalog catalog/field_catalog.json --answers answers --out <your output dir> --docs documents [--json report.json] [--quiet]

What it does
  1. Field scoring against the answer files in --answers (one <DOC_ID>.json per document).
  2. Mechanical provenance check: every extracted field must cite a span of the .txt that is its quote
     (case and runs of whitespace are ignored).
  3. Duplicate scoring when both --answers and --out contain duplicates.json.
  Every output file is scored. A file that cannot be read, or whose structure is not the schema's
  (cases not a list, a case or its fields not an object), scores 0 and is listed in the summary.

Scoring rules (per field, weight 1; the dose value+unit pair is one group, weight 2)
  correct                                 +1
  unknown                                  0   (or the field's unknown_credit when the answer allows it)
  wrong, confidence high                  -2
  wrong, confidence medium                -1
  wrong, confidence low                   -0.5
  answer expects unknown  -> unknown +1, conflict 0, any value = wrong
  answer expects conflict -> conflict +1, unknown +0.5, any value = wrong
  value fails catalog validation          = wrong
  invalid status, or a field that is not an object = wrong at high
  missing confidence                      = high
  list fields (string_list): every required item present, nothing forbidden, nothing unexpected.
      Items compare ignoring case, hyphens, word order and British or American spelling (ae, oe),
      so 'Rash maculo-papular' matches 'maculopapular rash' and 'oedema' matches 'edema'.
  document with no case: cases == []      +3 ; any case listed  -3
  answer case not produced                  -2 (its fields score 0) ; extra case  -2
  image-only document (allow_unsupported): flags contains unsupported_format and cases == [] -> excluded, 0 points, 0 max
  duplicates: each answer group found +3 ; each group that is not in the answer -3
"""
from __future__ import annotations
import argparse, json, math, os, re, sys
from typing import Any

WRONG = {"high": -2.0, "medium": -1.0, "low": -0.5}
CONF_RANK = {"low": 0, "medium": 1, "high": 2}
STATUSES = ("extracted", "unknown", "conflict")
NO_CASE_CORRECT, NO_CASE_WRONG, MISSING_CASE, EXTRA_CASE = 3.0, -3.0, -2.0, -2.0
DUP_FOUND, DUP_FALSE = 3.0, -3.0
ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
UNSUPPORTED_FLAG = "unsupported_format"
# British to American: haemorrhage, anaemia, oedema, diarrhoea; the oe after a vowel in angioedema stays.
SPELLING = ((re.compile(r"ae"), "e"), (re.compile(r"(?<![aeiou])oe"), "e"))
TERM_SEPARATORS = re.compile(r"[/,;()]")


# ----------------------------------------------------------------------------- helpers
def norm(s: Any) -> str:
    s = "" if s is None else str(s)
    s = s.replace("–", "-").replace("—", "-").replace("’", "'")
    s = re.sub(r"\s+", " ", s).strip().casefold()
    return s.rstrip(".")


def fold_spelling(word: str) -> str:
    for british, american in SPELLING:
        word = british.sub(american, word)
    return word


def term_keys(s: Any) -> set[str]:
    """Comparison keys for a list item: case, hyphens, word order and British or American spelling do not matter."""
    words = [pieces for pieces in ([fold_spelling(p) for p in w.split("-") if p] for w in TERM_SEPARATORS.sub(" ", norm(s)).split()) if pieces]
    spaced = " ".join(sorted(p for pieces in words for p in pieces))
    joined = " ".join(sorted(fold_spelling("".join(pieces)) for pieces in words))
    return {spaced, joined}


def conf_of(field: dict) -> str:
    c = field.get("confidence")
    return c if isinstance(c, str) and c in WRONG else "high"


def worst_conf(confs: list[str]) -> str:
    return max(confs, key=lambda c: CONF_RANK[c]) if confs else "high"


def load_json(path: str):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def shape_error(out: Any) -> str | None:
    """Why an output file cannot be scored, or None when its structure is readable."""
    if not isinstance(out, dict):
        return "the file is not a JSON object"
    if not isinstance(out.get("cases", []), list):
        return "cases is not a list"
    if not isinstance(out.get("flags", []), list):
        return "flags is not a list"
    for i, case in enumerate(out.get("cases", [])):
        if not isinstance(case, dict):
            return f"case {i} is not an object"
        if not isinstance(case.get("fields", {}), dict):
            return f"case {i}: fields is not an object"
    return None


def load_output(path: str) -> tuple[Any, str | None]:
    """The output file and None, or None and the reason it cannot be scored."""
    try:
        out = load_json(path)
    except OSError as e:
        return None, f"cannot read the file: {e}"
    except ValueError as e:  # JSONDecodeError and UnicodeDecodeError
        return None, f"not valid JSON: {e}"
    bad = shape_error(out)
    return (None, bad) if bad else (out, None)


def read_text(docs_dir: str, doc_id: str) -> str | None:
    p = os.path.join(docs_dir, f"{doc_id}.txt")
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8", newline="") as f:
        return f.read()


# ----------------------------------------------------------------------------- catalog
class Catalog:
    def __init__(self, data: dict):
        self.version = data.get("catalog_version")
        self.fields = {f["id"]: f for f in data["fields"]}

    def ftype(self, fid: str) -> str:
        return self.fields[fid]["type"]

    def validate(self, fid: str, value: Any) -> str | None:
        """Returns a reason when the value violates the catalog, else None."""
        f = self.fields.get(fid)
        if f is None:
            return "field id not in catalog"
        t = f["type"]
        v = f.get("validation", {})
        if t == "string":
            if not isinstance(value, str):
                return "string expected"
            if "pattern" in v and not re.match(v["pattern"], value):
                return f"does not match pattern {v['pattern']}"
        elif t in ("integer", "number"):
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                return "number expected"
            if not math.isfinite(value):
                return "finite number expected"
            if t == "integer" and float(value) != int(value):
                return "integer expected"
            if "min" in v and value < v["min"]:
                return f"below min {v['min']}"
            if "max" in v and value > v["max"]:
                return f"above max {v['max']}"
        elif t == "date":
            if not isinstance(value, str) or not ISO_DATE.match(value):
                return "ISO date YYYY-MM-DD expected"
        elif t == "coded":
            codes = {o["code"] for o in f["options"]}
            if not isinstance(value, str) or value not in codes:
                return f"not an option code: {value!r}"
        elif t == "coded_multi":
            codes = {o["code"] for o in f["options"]}
            if not isinstance(value, list) or not value:
                return "non-empty list of option codes expected"
            bad = [x for x in value if not isinstance(x, str) or x not in codes]
            if bad:
                return f"not option codes: {bad}"
            if "none" in value and len(value) > 1:
                return "'none' must stand alone"
        elif t == "string_list":
            if not isinstance(value, list) or not all(isinstance(x, str) for x in value):
                return "list of strings expected"
        return None


# ----------------------------------------------------------------------------- comparison
def values_match(ftype: str, out_value: Any, accept: list) -> bool:
    if ftype in ("integer", "number"):
        try:
            return any(abs(float(out_value) - float(a)) < 1e-6 for a in accept)
        except (TypeError, ValueError):
            return False
    return any(norm(out_value) == norm(a) for a in accept)


def match_list(out_items: list[str], spec: dict) -> tuple[bool, str]:
    """string_list: every required alt-set satisfied, nothing forbidden, nothing unexpected. Items compare by term_keys."""
    def alt_sets(kind: str) -> list[set[str]]:
        return [set().union(*(term_keys(a) for a in alts)) for alts in spec.get(kind, [])]
    required, optional, forbidden = alt_sets("required"), alt_sets("optional"), alt_sets("forbidden")
    satisfied = [False] * len(required)
    for item in out_items:
        keys = term_keys(item)
        if any(keys & alts for alts in forbidden):
            return False, f"forbidden item {item!r}"
        hit = False
        for i, alts in enumerate(required):
            if keys & alts:
                satisfied[i] = True
                hit = True
        if not hit and not any(keys & alts for alts in optional):
            return False, f"unexpected item {item!r}"
    missing = [norm(spec["required"][i][0]) for i, ok in enumerate(satisfied) if not ok]
    if missing:
        return False, f"missing required {missing}"
    return True, "ok"


def match_sets(out_value: list, accept_sets: list) -> bool:
    o = {norm(x) for x in out_value}
    return any(o == {norm(x) for x in s} for s in accept_sets)


# ----------------------------------------------------------------------------- field scoring
class FieldScore:
    def __init__(self, name, points, maximum, verdict, detail, conf=None):
        self.name, self.points, self.max, self.verdict, self.detail, self.conf = name, points, maximum, verdict, detail, conf


def score_field(cat: Catalog, fid: str, answer: dict, out: dict | None) -> FieldScore:
    if out is not None and not isinstance(out, dict):
        return FieldScore(fid, WRONG["high"], 1.0, "wrong", "field result is not an object", "high")
    status = (out or {}).get("status", "unknown")
    if status not in STATUSES:
        return FieldScore(fid, WRONG["high"], 1.0, "wrong", f"invalid status {status!r}", "high")
    conf = conf_of(out or {})
    expected = answer.get("expected")
    if expected == "unknown":
        if status == "unknown":
            return FieldScore(fid, 1.0, 1.0, "correct", "unknown as expected")
        if status == "conflict":
            return FieldScore(fid, 0.0, 1.0, "neutral", "conflict where answer is unknown")
        return FieldScore(fid, WRONG[conf], 1.0, "wrong", f"value {out.get('value')!r} where the document gives none", conf)
    if expected == "conflict":
        if status == "conflict":
            return FieldScore(fid, 1.0, 1.0, "correct", "conflict flagged")
        if status == "unknown":
            return FieldScore(fid, 0.5, 1.0, "partial", "unknown; answer expects conflict")
        return FieldScore(fid, WRONG[conf], 1.0, "wrong", f"picked {out.get('value')!r}; document contradicts itself {answer.get('values_seen')}", conf)
    # answer has a value
    if status == "unknown":
        credit = float(answer.get("unknown_credit", 0.0))
        return FieldScore(fid, credit, 1.0, "unknown", "unknown; answer has a value" + (" (credited)" if credit else ""))
    if status == "conflict":
        credit = float(answer.get("unknown_credit", 0.0))
        return FieldScore(fid, credit, 1.0, "unknown", "conflict; answer has one value")
    value = out.get("value")
    bad = cat.validate(fid, value)
    if bad:
        return FieldScore(fid, WRONG[conf], 1.0, "wrong", f"fails catalog validation: {bad}", conf)
    ftype = cat.ftype(fid)
    if ftype == "string_list":
        ok, why = match_list(value, answer)
        return FieldScore(fid, 1.0 if ok else WRONG[conf], 1.0, "correct" if ok else "wrong", why, None if ok else conf)
    if ftype == "coded_multi":
        ok = match_sets(value, answer.get("accept_sets", []))
        return FieldScore(fid, 1.0 if ok else WRONG[conf], 1.0, "correct" if ok else "wrong", "ok" if ok else f"{value} not an accepted set", None if ok else conf)
    ok = values_match(ftype, value, answer.get("accept", []))
    return FieldScore(fid, 1.0 if ok else WRONG[conf], 1.0, "correct" if ok else "wrong", "ok" if ok else f"{value!r} not in {answer.get('accept')}", None if ok else conf)


def score_group(cat: Catalog, grp: dict, fields_out: dict) -> FieldScore:
    name = grp["name"]
    members = grp["fields"]
    raw = [fields_out.get(f) for f in members]
    if any(r is not None and not isinstance(r, dict) for r in raw):
        return FieldScore(name, 2 * WRONG["high"], 2.0, "wrong", "field result is not an object", "high")
    outs = [r or {"status": "unknown"} for r in raw]
    statuses = [o.get("status", "unknown") for o in outs]
    confs = [conf_of(o) for o in outs if o.get("status") == "extracted"]
    conf = worst_conf(confs)
    if all(s == "unknown" for s in statuses):
        credit = 2.0 * float(grp.get("unknown_credit", 0.0))
        return FieldScore(name, credit, 2.0, "unknown", "all members unknown" + (" (credited)" if credit else ""))
    if any(s == "conflict" for s in statuses):
        return FieldScore(name, 0.0, 2.0, "unknown", "conflict in group")
    if any(s != "extracted" for s in statuses):
        return FieldScore(name, 2 * WRONG[conf], 2.0, "wrong", "partial group (a value without its unit is unusable)", conf)
    for f, o in zip(members, outs):
        bad = cat.validate(f, o.get("value"))
        if bad:
            return FieldScore(name, 2 * WRONG[conf], 2.0, "wrong", f"{f} fails catalog validation: {bad}", conf)
    tup = [o.get("value") for o in outs]
    for acc in grp["accept"]:
        if all(values_match(cat.ftype(f), v, [a]) for f, v, a in zip(members, tup, acc)):
            return FieldScore(name, 2.0, 2.0, "correct", "ok")
    return FieldScore(name, 2 * WRONG[conf], 2.0, "wrong", f"{tup} not in {grp['accept']}", conf)


def score_case(cat: Catalog, gold_case: dict, out_case: dict | None) -> list[FieldScore]:
    fields_out = (out_case or {}).get("fields", {})
    grouped = {f for g in gold_case.get("groups", []) for f in g["fields"]}
    scores = []
    for fid, g in gold_case["fields"].items():
        if fid in grouped:
            continue
        scores.append(score_field(cat, fid, g, fields_out.get(fid)))
    for grp in gold_case.get("groups", []):
        scores.append(score_group(cat, grp, fields_out))
    return scores


def pair_cases(cat: Catalog, gold_cases: list, out_cases: list) -> list[tuple[int, int | None]]:
    """Greedy pairing by the number of correct fields."""
    if len(gold_cases) == 1 and len(out_cases) == 1:
        return [(0, 0)]
    sim = {}
    for gi, g in enumerate(gold_cases):
        for oi, o in enumerate(out_cases):
            sim[(gi, oi)] = sum(1 for s in score_case(cat, g, o) if s.verdict == "correct")
    pairs, used_g, used_o = [], set(), set()
    for (gi, oi), _ in sorted(sim.items(), key=lambda kv: -kv[1]):
        if gi in used_g or oi in used_o:
            continue
        pairs.append((gi, oi)); used_g.add(gi); used_o.add(oi)
    for gi in range(len(gold_cases)):
        if gi not in used_g:
            pairs.append((gi, None))
    return pairs


# ----------------------------------------------------------------------------- provenance
def check_provenance(doc_text: str | None, out_doc: dict) -> list[dict]:
    results = []
    for case in out_doc.get("cases", []):
        for fid, f in case.get("fields", {}).items():
            if not isinstance(f, dict) or f.get("status") != "extracted":
                continue
            r = {"case": case.get("case_index"), "field": fid, "ok": False, "reason": ""}
            prov = f.get("provenance")
            if not prov:
                r["reason"] = "no provenance"
            elif not isinstance(prov, list) or not all(isinstance(p, dict) for p in prov):
                r["reason"] = "provenance is not a list of objects"
            elif doc_text is None:
                r["ok"], r["reason"] = None, "not checkable (no .txt for this document)"
            else:
                problems = []
                for p in prov:
                    q, s, e = p.get("quote", ""), p.get("start"), p.get("end")
                    if not isinstance(s, int) or not isinstance(e, int) or s < 0 or e > len(doc_text) or s >= e:
                        problems.append(f"bad span [{s}:{e}] (doc length {len(doc_text)})"); continue
                    if len(norm(q)) < 3:
                        problems.append("quote shorter than 3 characters"); continue
                    span = norm(doc_text[s:e])
                    if span != norm(q):
                        if norm(q) in span:
                            problems.append(f"span [{s}:{e}] is wider than the quote"); continue
                        idx = norm(doc_text).find(norm(q))
                        hint = " (quote does occur elsewhere in the document)" if idx >= 0 else " (quote not found anywhere in the document)"
                        problems.append(f"span [{s}:{e}] does not contain the quote{hint}")
                r["ok"] = not problems
                r["reason"] = "; ".join(problems) if problems else "grounded"
            results.append(r)
    return results


# ----------------------------------------------------------------------------- duplicates
def dup_groups(data: Any) -> tuple[set[frozenset], str | None]:
    """The groups of a duplicates file as sets of document ids, and what was wrong with the file, if anything."""
    groups = data.get("groups") if isinstance(data, dict) else None
    if not isinstance(groups, list):
        return set(), "groups is not a list, scored as empty"
    good = [g["document_ids"] for g in groups if isinstance(g, dict) and isinstance(g.get("document_ids"), list)
            and all(isinstance(x, str) for x in g["document_ids"])]
    skipped = len(groups) - len(good)
    return {frozenset(ids) for ids in good}, (f"{skipped} malformed group(s) skipped" if skipped else None)


def score_duplicates(answer: dict, out: Any) -> dict:
    gold_groups, _ = dup_groups(answer)
    out_groups, problem = dup_groups(out)
    found = gold_groups & out_groups
    false = out_groups - gold_groups
    missed = gold_groups - out_groups
    points = DUP_FOUND * len(found) + DUP_FALSE * len(false)
    result = {"found": sorted(sorted(g) for g in found), "missed": sorted(sorted(g) for g in missed),
              "false": sorted(sorted(g) for g in false), "points": points, "max": DUP_FOUND * len(gold_groups)}
    if problem:
        result["note"] = problem
    return result


# ----------------------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--catalog", required=True)
    ap.add_argument("--answers", required=True, help="directory with <DOC_ID>.json answer files (and optionally duplicates.json)")
    ap.add_argument("--out", required=True, help="directory with your <DOC_ID>.json outputs (and optionally duplicates.json)")
    ap.add_argument("--docs", required=True, help="directory with the source .txt files, for the provenance check")
    ap.add_argument("--json", help="write a machine-readable report here")
    ap.add_argument("--quiet", action="store_true", help="only the summary")
    a = ap.parse_args()

    cat = Catalog(load_json(a.catalog))
    report = {"documents": [], "totals": {}}
    total_pts = total_max = 0.0
    wrong_by_conf = {"high": 0, "medium": 0, "low": 0}
    unknown_with_value = case_errors = 0
    prov_total = prov_ok = prov_unchecked = 0
    unreadable = []
    say = (lambda *x: None) if a.quiet else print

    gold_ids = sorted(f[:-5] for f in os.listdir(a.answers) if f.endswith(".json") and f != "duplicates.json")
    for doc_id in gold_ids:
        answer = load_json(os.path.join(a.answers, f"{doc_id}.json"))
        out_path = os.path.join(a.out, f"{doc_id}.json")
        out, why = load_output(out_path) if os.path.exists(out_path) else (None, None)
        doc = {"document_id": doc_id, "fields": [], "notes": [], "points": 0.0, "max": 0.0, "provenance": []}
        say(f"\n=== {doc_id} ===")
        if out is None:
            doc["notes"].append(f"output not scorable, scored 0: {why}" if why else "no output file")
            doc["max"] = sum(1 for c in answer["cases"] for f in c["fields"] if f not in {x for g in c.get("groups", []) for x in g["fields"]}) \
                         + sum(2 for c in answer["cases"] for _ in c.get("groups", [])) + (NO_CASE_CORRECT if not answer["cases"] else 0)
            if why: unreadable.append(doc_id)
            say(f"  output not scorable, scored 0: {why}" if why else "  no output file: all fields unknown")
            total_max += doc["max"]; report["documents"].append(doc); continue

        gold_cases, out_cases, flags = answer["cases"], out.get("cases", []), out.get("flags", [])
        if answer.get("allow_unsupported") and UNSUPPORTED_FLAG in flags and not out_cases:
            doc["notes"].append("declared unsupported_format with no cases: excluded from scoring (0 points, 0 max)")
            say("  declared unsupported_format, no cases: excluded")
            report["documents"].append(doc); continue

        if not gold_cases:
            pts = NO_CASE_CORRECT if not out_cases else NO_CASE_WRONG
            if out_cases: case_errors += 1
            doc["points"], doc["max"] = pts, NO_CASE_CORRECT
            say(f"  answer has no case; output has {len(out_cases)} -> {pts:+.1f}")
        else:
            pairs = pair_cases(cat, gold_cases, out_cases)
            extra = len(out_cases) - sum(1 for _, oi in pairs if oi is not None)
            for gi, oi in pairs:
                g = gold_cases[gi]; o = out_cases[oi] if oi is not None else None
                say(f"  -- answer case {gi} '{g.get('label', '')}' -> output case {oi if oi is not None else 'MISSING'}")
                if o is None:
                    doc["points"] += MISSING_CASE; case_errors += 1
                    doc["notes"].append(f"answer case {gi} not produced ({MISSING_CASE:+.0f}, its fields score 0)")
                for s in score_case(cat, g, o):
                    if o is None:
                        s = FieldScore(s.name, 0.0, s.max, "missing", "case not produced")
                    doc["points"] += s.points; doc["max"] += s.max
                    if s.verdict == "wrong" and s.conf: wrong_by_conf[s.conf] += 1
                    if s.verdict == "unknown": unknown_with_value += 1
                    doc["fields"].append({"case": gi, "field": s.name, "points": s.points, "max": s.max, "verdict": s.verdict, "detail": s.detail, "confidence": s.conf})
                    mark = {"correct": "ok ", "wrong": "XX ", "unknown": "-- ", "partial": "~  ", "neutral": "-- ", "missing": "-- "}[s.verdict]
                    say(f"     {mark}{s.name:28s} {s.points:+5.1f}  {s.detail}" + (f"  [conf {s.conf}]" if s.conf else ""))
            if extra > 0:
                doc["points"] += EXTRA_CASE * extra; case_errors += extra
                doc["notes"].append(f"{extra} extra case(s) ({EXTRA_CASE * extra:+.0f})")
        # provenance
        text = read_text(a.docs, doc_id)
        prov = check_provenance(text, out)
        doc["provenance"] = prov
        for r in prov:
            if r["ok"] is None: prov_unchecked += 1
            else:
                prov_total += 1; prov_ok += 1 if r["ok"] else 0
                if not r["ok"]: say(f"     !! provenance  case {r['case']} {r['field']}: {r['reason']}")
        for n in doc["notes"]: say(f"  note: {n}")
        say(f"  {doc_id}: {doc['points']:.1f} / {doc['max']:.0f}")
        total_pts += doc["points"]; total_max += doc["max"]
        report["documents"].append(doc)

    dup = None
    gdup, odup = os.path.join(a.answers, "duplicates.json"), os.path.join(a.out, "duplicates.json")
    if os.path.exists(gdup):
        out_dup, dup_note = {"groups": []}, "no duplicates.json in output"
        if os.path.exists(odup):
            try:
                out_dup, dup_note = load_json(odup), None
            except (OSError, ValueError) as e:
                dup_note = f"duplicates.json not readable, scored as empty: {e}"
        dup = score_duplicates(load_json(gdup), out_dup)
        if dup_note: dup["note"] = dup_note
        report["duplicates"] = dup

    pct = max(0.0, total_pts) / total_max * 100 if total_max else 0.0
    t = report["totals"] = {
        "documents_scored": len(gold_ids), "field_points": round(total_pts, 2), "field_max": total_max, "field_percent": round(pct, 1),
        "wrong_high": wrong_by_conf["high"], "wrong_medium": wrong_by_conf["medium"], "wrong_low": wrong_by_conf["low"],
        "unknown_where_answer_has_value": unknown_with_value, "case_count_errors": case_errors,
        "provenance_checked": prov_total, "provenance_grounded": prov_ok, "provenance_unchecked": prov_unchecked,
        "provenance_percent": round(prov_ok / prov_total * 100, 1) if prov_total else None,
        "outputs_not_scorable": unreadable,
    }
    print("\n================ SUMMARY ================")
    print(f"Documents scored:      {t['documents_scored']}")
    if unreadable:
        print(f"Outputs not scorable:  {', '.join(unreadable)} (scored 0; see each document's note)")
    print(f"Field points:          {t['field_points']} / {t['field_max']:.0f}  ({t['field_percent']}%)")
    print(f"Wrong at confidence:   high {t['wrong_high']}   medium {t['wrong_medium']}   low {t['wrong_low']}")
    print(f"Unknown, answer had one: {t['unknown_where_answer_has_value']}")
    print(f"Case-count errors:     {t['case_count_errors']}")
    if dup:
        print(f"Duplicates:            {len(dup['found'])} of {len(dup['found']) + len(dup['missed'])} answer groups found, {len(dup['false'])} false group(s) -> {dup['points']:+.0f} / {dup['max']:.0f}" + (f"  ({dup['note']})" if dup.get("note") else ""))
    if prov_total:
        print(f"Provenance:            {prov_ok} of {prov_total} extracted fields grounded ({t['provenance_percent']}%)" + (f", {prov_unchecked} not checkable" if prov_unchecked else ""))
    else:
        print("Provenance:            nothing to check")
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
        print(f"Report written:        {a.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
