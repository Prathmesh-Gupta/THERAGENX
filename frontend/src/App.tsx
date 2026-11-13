import { useEffect, useMemo, useRef, useState } from 'react'
import './App.css'

type AnyRecord = Record<string, any>
type DocItem = { id: string; name?: string; status?: string; extractionStatus?: string; [key: string]: any }

const API = (import.meta as any).env?.VITE_API_BASE_URL ?? ''

function getId(item: any): string {
  return String(item?.id ?? item?.documentId ?? item?.document_id ?? item?.name ?? '')
}
function getFieldValue(field: any): string {
  if (field == null) return '—'
  const value = field.value
  if (value == null || value === '') return field.status === 'unknown' ? 'Not stated' : '—'
  return Array.isArray(value) ? value.join(', ') : String(value)
}
function normalizeDocuments(data: any): DocItem[] {
  const items = Array.isArray(data) ? data : data?.documents ?? data?.items ?? []
  return items.map((item: any) => typeof item === 'string' ? { id: item } : { ...item, id: getId(item) }).filter((x: DocItem) => x.id)
}
function normalizeFields(data: any, caseIndex = 0): Array<{ id: string; label: string; value: string; status: string; confidence: string; evidence: any[]; group: string }> {
  const root = data?.result ?? data?.extraction ?? data
  const cases = root?.cases ?? []
  const firstCase = cases[caseIndex] ?? root
  const fields = firstCase?.fields ?? {}
  return Object.entries(fields).map(([id, raw]: [string, any]) => ({
    id, label: raw?.label ?? raw?.name ?? id.replaceAll('_', ' '),
    value: getFieldValue(raw), status: raw?.status ?? 'unknown',
    confidence: raw?.confidence ?? '—', evidence: raw?.evidence ?? [],
    group: raw?.group ?? raw?.section ?? 'Extracted fields',
  }))
}

function App() {
  const [theme, setTheme] = useState<'light' | 'dark'>(() => (localStorage.getItem('theragenx-theme') as 'light' | 'dark') || 'light')
  const [documents, setDocuments] = useState<DocItem[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [detail, setDetail] = useState<AnyRecord | null>(null)
  const [catalog, setCatalog] = useState<AnyRecord | null>(null)
  const [loadingList, setLoadingList] = useState(true)
  const [loadingDetail, setLoadingDetail] = useState(false)
  const [extracting, setExtracting] = useState(false)
  const [extractionError, setExtractionError] = useState('')
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [activeField, setActiveField] = useState('')
  const [activeEvidenceIndex, setActiveEvidenceIndex] = useState(0)
  const [activeCaseIndex, setActiveCaseIndex] = useState(0)
  const [reextracting, setReextracting] = useState(false)
  const [reviewData, setReviewData] = useState<AnyRecord | null>(null)
  const [reviewBusy, setReviewBusy] = useState(false)
  const [reviewMessage, setReviewMessage] = useState('')
  const [editOpen, setEditOpen] = useState(false)
  const [editDraft, setEditDraft] = useState<AnyRecord | null>(null)
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [duplicateInfo, setDuplicateInfo] = useState<AnyRecord | null>(null)
  const [uploadBusy, setUploadBusy] = useState(false)
  const [uploadMessage, setUploadMessage] = useState('')
  const [catalogOpen, setCatalogOpen] = useState(false)
  const [catalogDraft, setCatalogDraft] = useState<AnyRecord | null>(null)
  const [catalogBusy, setCatalogBusy] = useState(false)
  const autoStartedRef = useRef<Set<string>>(new Set())

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('theragenx-theme', theme)
  }, [theme])

  useEffect(() => {
    let alive = true
    Promise.all([
      fetch(`${API}/api/documents`).then(r => { if (!r.ok) throw new Error(`Documents API returned ${r.status}`); return r.json() }),
      fetch(`${API}/api/catalog`).then(r => { if (!r.ok) throw new Error(`Catalog API returned ${r.status}`); return r.json() }).catch(() => null),
    ]).then(([docs, cat]) => {
      if (!alive) return
      const list = normalizeDocuments(docs)
      setDocuments(list)
      setCatalog(cat)
      if (list.length) setSelectedId(list[0].id)
    }).catch(e => alive && setError(`${e.message}. Check that the Spring Boot backend is running.`))
      .finally(() => alive && setLoadingList(false))
    return () => { alive = false }
  }, [])

  useEffect(() => {
    if (!selectedId) return
    let alive = true
    setReviewData(null)
    fetch(`${API}/api/reviews/${encodeURIComponent(selectedId)}`)
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (alive) setReviewData(data) })
      .catch(() => { if (alive) setReviewData(null) })
    return () => { alive = false }
  }, [selectedId])

  useEffect(() => {
    if (!selectedId) return
    setReviewMessage('')
    let alive = true
    setLoadingDetail(true)
    setError('')
    fetch(`${API}/api/documents/${encodeURIComponent(selectedId)}`)
      .then(r => { if (!r.ok) throw new Error(`Document API returned ${r.status}`); return r.json() })
      .then(data => alive && setDetail(data))
      .catch(e => alive && setError(`${e.message}. Unable to load ${selectedId}.`))
      .finally(() => alive && setLoadingDetail(false))
    return () => { alive = false }
  }, [selectedId])

  useEffect(() => {
    const caseDecisions = reviewData?.caseDecisions
    if (!caseDecisions || !detail) return
    setDetail(previous => {
      if (!previous) return previous
      const next = JSON.parse(JSON.stringify(previous))
      const root = next.result ?? next.extraction ?? next
      if (!Array.isArray(root.cases)) return previous
      let changed = false
      Object.entries(caseDecisions).forEach(([index, decision]: [string, any]) => {
        const caseIndex = Number(index)
        if (decision?.action === 'EDITED' && decision?.editedCase && root.cases[caseIndex]) {
          root.cases[caseIndex] = decision.editedCase
          changed = true
        }
      })
      return changed ? next : previous
    })
  }, [reviewData, selectedId])

  useEffect(() => {
    const edited = reviewData?.documentDecision?.editedExtraction
    if (reviewData?.documentDecision?.action === 'EDITED' && edited) {
      setDetail(previous => previous?.result === edited ? previous : ({ ...(previous ?? {}), result: edited }))
    }
  }, [reviewData])

  const rootExtraction = detail?.result ?? detail?.extraction ?? detail
  const extractionCases: any[] = Array.isArray(rootExtraction?.cases) ? rootExtraction.cases : []
  const activeCase = extractionCases[activeCaseIndex] ?? null
  const fields = useMemo(() => normalizeFields(detail, activeCaseIndex), [detail, activeCaseIndex])
  const reviewWarnings = useMemo(() => {
    const root = detail?.result ?? detail?.extraction ?? detail
    const cases = root?.cases ?? []

    const caseWarnings = cases.flatMap((item: any, index: number) =>
      (item?.caseValidation?.errors ?? []).map(
        (message: string) => `Case ${index + 1}: ${message}`
      )
    )

    const validationWarnings = detail?.validation?.errors ?? []
    const extractionFlags = (root?.flags ?? []).map((flag: any) => String(flag))

    return [...new Set([...caseWarnings, ...validationWarnings, ...extractionFlags])]
  }, [detail])
  const filteredDocs = documents.filter(d => d.id.toLowerCase().includes(query.toLowerCase()))
  const selectedDocument = documents.find(d => d.id === selectedId)
  const rawDocumentStatus = String(selectedDocument?.extractionStatus ?? selectedDocument?.status ?? 'NOT_PROCESSED').toUpperCase()
  const documentStatus = rawDocumentStatus === 'PROCESSED' ? 'COMPLETED' : rawDocumentStatus
  const docText = detail?.content ?? detail?.documentText ?? detail?.text ?? detail?.document?.content ?? ''
  const groups = useMemo(() => fields.reduce<Record<string, typeof fields>>((acc, field) => {
    (acc[field.group] ??= []).push(field)
    return acc
  }, {}), [fields])

  const activeEvidence = fields.find(field => field.id === activeField)?.evidence ?? []
  const selectedEvidence = activeEvidence[activeEvidenceIndex]
  const activeRanges = (selectedEvidence ? [selectedEvidence] : [])
    .filter((ev: any) => Number.isInteger(ev?.start) && Number.isInteger(ev?.end) && ev.start >= 0 && ev.end > ev.start && ev.end <= docText.length)
    .map((ev: any) => ({ start: ev.start, end: ev.end }))
    .sort((a, b) => a.start - b.start)

  // Merge overlapping evidence ranges so the source text is rendered only once.
  const mergedRanges = activeRanges.reduce<Array<{ start: number; end: number }>>((ranges, range) => {
    const last = ranges[ranges.length - 1]
    if (last && range.start <= last.end) last.end = Math.max(last.end, range.end)
    else ranges.push({ ...range })
    return ranges
  }, [])

  useEffect(() => {
    if (!activeField || !activeRanges.length) return
    const target = document.getElementById(`evidence-offset-${activeRanges[0].start}`)
    const reportContainer = document.getElementById('source-report')
    if (!target || !reportContainer) return

    // Scroll only the source report, never the page or extracted-fields panel.
    const containerRect = reportContainer.getBoundingClientRect()
    const targetRect = target.getBoundingClientRect()
    const offsetWithinContainer = targetRect.top - containerRect.top
    const nextScrollTop = reportContainer.scrollTop + offsetWithinContainer
      - reportContainer.clientHeight / 2 + targetRect.height / 2

    reportContainer.scrollTo({ top: nextScrollTop, behavior: 'smooth' })
  }, [activeField, activeEvidenceIndex, selectedId, detail])

  async function runExtraction() {
    if (!selectedId || extracting) return
    setExtracting(true)
    setExtractionError('')
    setActiveField('')
    try {
      const response = await fetch(`${API}/api/extractions/${encodeURIComponent(selectedId)}`, { method: 'POST' })
      const responseText = await response.text()
      let payload: any
      try {
        payload = responseText ? JSON.parse(responseText) : {}
      } catch {
        throw new Error(`Extraction API returned an unexpected response (${response.status}). Check the Spring Boot logs.`)
      }
      if (!response.ok) throw new Error(payload?.message ?? payload?.error ?? `Extraction API returned ${response.status}`)

      const resultJson = payload?.result ?? payload?.extraction ?? payload
      setDetail(previous => ({ ...(previous ?? {}), result: resultJson, validation: payload?.validation ?? null }))
      const returnedStatus = String(payload?.status ?? resultJson?.status ?? 'PROCESSED').toUpperCase()
      const nextStatus = returnedStatus === 'PROCESSED' ? 'COMPLETED' : returnedStatus
      setDocuments(previous => previous.map(item => item.id === selectedId
        ? { ...item, extractionStatus: nextStatus }
        : item))
    } catch (e: any) {
      setExtractionError(e?.message ?? 'Extraction failed. Please try again.')
      setDocuments(previous => previous.map(item => item.id === selectedId
        ? { ...item, extractionStatus: 'FAILED' }
        : item))
    } finally {
      setExtracting(false)
    }
  }


  async function reextractSelectedDocument() {
    if (!selectedId || extracting || reextracting || loadingDetail) return
    setReextracting(true)
    setExtractionError('')
    setActiveField('')

    try {
      const response = await fetch(
        `${API}/api/extractions/${encodeURIComponent(selectedId)}/reprocess`,
        { method: 'POST' }
      )
      const responseText = await response.text()
      let payload: any
      try {
        payload = responseText ? JSON.parse(responseText) : {}
      } catch {
        throw new Error(`Re-extraction API returned an unexpected response (${response.status}). Check the Spring Boot logs.`)
      }
      if (!response.ok) {
        throw new Error(payload?.message ?? payload?.error ?? `Re-extraction API returned ${response.status}`)
      }

      const resultJson = payload?.result ?? payload?.extraction ?? payload
      setDetail(previous => ({ ...(previous ?? {}), result: resultJson, validation: payload?.validation ?? null }))
      const returnedStatus = String(payload?.status ?? resultJson?.status ?? 'PROCESSED').toUpperCase()
      const nextStatus = returnedStatus === 'PROCESSED' ? 'COMPLETED' : returnedStatus
      setDocuments(previous => previous.map(item => item.id === selectedId
        ? { ...item, extractionStatus: nextStatus }
        : item))
    } catch (e: any) {
      // Keep the last successful extraction and document status visible on failure.
      setExtractionError(e?.message ?? 'Re-extraction failed. The previous saved result has been retained.')
    } finally {
      setReextracting(false)
    }
  }


  async function saveDocumentDecision(action: 'ACCEPTED' | 'REJECTED' | 'EDITED', editedExtraction?: AnyRecord) {
    if (!selectedId || reviewBusy) return
    setReviewBusy(true)
    setReviewMessage('')
    try {
      const response = await fetch(`${API}/api/reviews/${encodeURIComponent(selectedId)}`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ scope: 'DOCUMENT', action, ...(editedExtraction ? { editedExtraction } : {}) }),
      })
      const payload = await response.json().catch(() => ({}))
      if (!response.ok) throw new Error(payload?.message ?? payload?.error ?? `Review save failed (${response.status})`)
      setReviewData(payload)
      if (action === 'EDITED' && editedExtraction) setDetail(previous => ({ ...(previous ?? {}), result: editedExtraction }))
      setEditOpen(false)
      setReviewMessage(action === 'ACCEPTED' ? 'Full extraction accepted.' : action === 'REJECTED' ? 'Full extraction rejected.' : 'Full extraction edits saved.')
    } catch (e: any) {
      setReviewMessage(e?.message ?? 'Unable to save review decision.')
    } finally { setReviewBusy(false) }
  }

  async function saveCaseDecision(action: 'ACCEPTED' | 'REJECTED' | 'EDITED', editedCase?: AnyRecord) {
    if (!selectedId || reviewBusy) return
    setReviewBusy(true)
    setReviewMessage('')
    try {
      const response = await fetch(`${API}/api/reviews/${encodeURIComponent(selectedId)}`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ scope: 'CASE', caseIndex: activeCaseIndex, action, ...(editedCase ? { editedCase } : {}) }),
      })
      const payload = await response.json().catch(() => ({}))
      if (!response.ok) throw new Error(payload?.message ?? payload?.error ?? `Case review save failed (${response.status})`)
      setReviewData(payload)
      if (action === 'EDITED' && editedCase) {
        setDetail(previous => {
          if (!previous) return previous
          const next = JSON.parse(JSON.stringify(previous))
          const root = next.result ?? next.extraction ?? next
          root.cases[activeCaseIndex] = editedCase
          return next
        })
      }
      setReviewMessage(`Case ${activeCaseIndex + 1} ${action.toLowerCase()}.`)
      setEditOpen(false)
    } catch (e: any) {
      setReviewMessage(e?.message ?? 'Unable to save case decision.')
    } finally { setReviewBusy(false) }
  }

  function beginCaseEdit() {
    if (!activeCase) return
    const draft = JSON.parse(JSON.stringify(activeCase))
    setEditDraft({ document_id: selectedId, cases: [draft], flags: rootExtraction?.flags ?? [] })
    setEditOpen(true)
  }


  function updateDraftField(caseIndex: number, fieldId: string, rawValue: string) {
    setEditDraft((previous: AnyRecord | null) => {
      if (!previous) return previous
      const next = JSON.parse(JSON.stringify(previous))
      const field = next.cases?.[caseIndex]?.fields?.[fieldId]
      if (!field) return previous
      const current = field.value
      if (Array.isArray(current) || ['event_terms', 'concomitant_drugs', 'seriousness_criteria'].includes(fieldId)) field.value = rawValue.split(',').map(x => x.trim()).filter(Boolean)
      else if (typeof current === 'number' || fieldId === 'patient_age_years' || fieldId === 'suspect_drug_dose_value') field.value = rawValue.trim() === '' ? null : Number(rawValue)
      else field.value = rawValue.trim() === '' ? null : rawValue
      field.status = field.value == null || (Array.isArray(field.value) && field.value.length === 0) ? 'unknown' : 'extracted'
      if (field.status === 'unknown') { field.confidence = 'low'; field.evidence = [] }
      else { field.confidence = 'medium'; field.note = 'Reviewer-edited value; verify against the source report.' }
      return next
    })
  }

  async function refreshDocumentList(selectId?: string) {
    const response = await fetch(`${API}/api/documents`)
    if (!response.ok) throw new Error(`Documents API returned ${response.status}`)
    const list = normalizeDocuments(await response.json())
    setDocuments(list)
    if (selectId) setSelectedId(selectId)
  }

  async function checkAndUploadDocument(file: File, allowDuplicate = false) {
    setUploadBusy(true); setUploadMessage(''); setDuplicateInfo(null)
    try {
      if (!allowDuplicate) {
        const form = new FormData(); form.append('file', file)
        const check = await fetch(`${API}/api/documents/upload/check`, { method: 'POST', body: form })
        const info = await check.json()
        if (!check.ok) throw new Error(info.message ?? `Duplicate check failed (${check.status})`)
        if (info.duplicate) { setDuplicateInfo(info); return }
      }
      const form = new FormData(); form.append('file', file)
      const response = await fetch(`${API}/api/documents/upload${allowDuplicate ? '?allowDuplicate=true' : ''}`, { method: 'POST', body: form })
      const result = await response.json()
      if (!response.ok) throw new Error(result.message ?? `Upload failed (${response.status})`)
      await refreshDocumentList(result.documentId)
      setUploadFile(null); setDuplicateInfo(null); setUploadMessage(`Uploaded ${result.documentId}. Select it to start extraction.`)
    } catch (e: any) { setUploadMessage(e?.message ?? 'Upload failed.') }
    finally { setUploadBusy(false) }
  }

  function updateCatalogField(index: number, key: string, value: any) {
    setCatalogDraft(previous => {
      if (!previous) return previous
      const next = JSON.parse(JSON.stringify(previous))
      next.fields[index][key] = value
      return next
    })
  }

  function addCatalogField() {
    setCatalogDraft(previous => {
      if (!previous) return previous
      const next = JSON.parse(JSON.stringify(previous))
      next.fields = [...(next.fields ?? []), { id: `new_field_${(next.fields ?? []).length + 1}`, label: 'New field', section: next.sections?.[0]?.id ?? 'patient', type: 'string', guidance: '' }]
      return next
    })
  }

  function removeCatalogField(index: number) {
    setCatalogDraft(previous => {
      if (!previous) return previous
      const next = JSON.parse(JSON.stringify(previous))
      next.fields.splice(index, 1)
      return next
    })
  }

  function updateCatalogOptions(index: number, raw: string) {
    const options = raw.split('\n').map(line => line.trim()).filter(Boolean).map(line => {
      const split = line.indexOf('|')
      return split < 0 ? { code: line, label: line } : { code: line.slice(0, split).trim(), label: line.slice(split + 1).trim() }
    })
    updateCatalogField(index, 'options', options)
  }

  async function saveCatalogDraft() {
    if (!catalogDraft) return
    setCatalogBusy(true)
    try {
      const response = await fetch(`${API}/api/catalog`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(catalogDraft) })
      const result = await response.json().catch(() => ({}))
      if (!response.ok) throw new Error(result.message ?? result.error ?? `Catalog save failed (${response.status})`)
      setCatalog(catalogDraft); setCatalogOpen(false); setUploadMessage('Catalog saved and activated. New extractions will use these fields.')
    } catch (e: any) { setUploadMessage(e?.message ?? 'Catalog save failed.') }
    finally { setCatalogBusy(false) }
  }

  // Automatically extract a document the first time it is selected if it has no saved result.
  // Completed documents are never sent to the LLM automatically.
  useEffect(() => {
    if (!selectedId || loadingDetail || extracting || reextracting) return
    if (documentStatus !== 'NOT_PROCESSED') return
    if (autoStartedRef.current.has(selectedId)) return
    autoStartedRef.current.add(selectedId)
    runExtraction()
  }, [selectedId, loadingDetail, documentStatus, extracting, reextracting])

  return (
    <main className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">T</div>
          <div><div className="brand-name">THERAGENX</div><div className="brand-caption">CASE REVIEW WORKSPACE</div></div>
        </div>
        <div className="sidebar-documents">
          <div className="sidebar-list-heading"><span>DOCUMENTS</span><span>{documents.length.toString().padStart(2, '0')}</span></div>
          <div className="sidebar-search"><span>⌕</span><input value={query} onChange={e => setQuery(e.target.value)} placeholder="Find document..." /></div>
          <div className="sidebar-document-list">
            {loadingList && <div className="empty-state">Loading…</div>}
            {!loadingList && filteredDocs.map(doc => {
              const status = String(doc.extractionStatus ?? doc.status ?? 'NOT_PROCESSED').toUpperCase()
              const count = doc.caseCount ?? doc.casesCount
              return <button key={doc.id} className={`sidebar-document ${selectedId === doc.id ? 'selected' : ''}`} onClick={() => { setSelectedId(doc.id); setActiveField(''); setActiveCaseIndex(0) }}>
                <span className="sidebar-doc-icon">▤</span><span className="sidebar-doc-info"><strong>{doc.id}</strong><small>{doc.name ?? 'Safety report'}</small><small className={`doc-status-text ${status.toLowerCase().replaceAll('_','-')}`}>{status.replaceAll('_',' ')}{count ? ` · ${count} cases` : ''}</small></span>
              </button>
            })}
            {!loadingList && !filteredDocs.length && <div className="empty-state">No documents found.</div>}
          </div>
          <div className="sidebar-bottom"><span className="status-dot" /> Local workspace <span className="version">v1.0</span></div>
        </div>
      </aside>

      <section className="main-area">
        <header className="topbar">
          <div className="breadcrumb">Workspace <span>/</span> <strong>Documents</strong></div>
          <div className="top-actions"><label className="review-action upload-trigger">Upload document<input type="file" accept=".pdf,.txt" hidden onChange={e => { const f=e.target.files?.[0]; if(f){setUploadFile(f); checkAndUploadDocument(f)} e.currentTarget.value='' }} /></label><button className="review-action" onClick={() => { setCatalogDraft(JSON.parse(JSON.stringify(catalog ?? {}))); setCatalogOpen(true) }}>Edit catalog</button>
            <span className="connection"><i /> Backend connection</span>
            <button className="theme-toggle" onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')} aria-label="Toggle light and dark mode">
              <span>{theme === 'light' ? '☾' : '☀'}</span> {theme === 'light' ? 'Dark mode' : 'Light mode'}
            </button>
          </div>
        </header>

        <div className="page-heading">
          <div><div className="eyebrow">SAFETY CASE MANAGEMENT</div><h1>Document review</h1><p className="subtitle">Review extracted information against the original report.</p></div>
          <div className="heading-meta"><span className="meta-label">DOCUMENTS</span><strong>{documents.length.toString().padStart(2, '0')}</strong></div>
        </div>

        {uploadMessage && <div className="alert upload-alert">{uploadMessage}<button onClick={() => setUploadMessage('')}>×</button></div>}
        {error && <div className="alert"><strong>Connection issue</strong><span>{error}</span></div>}

        <div className="workspace-grid">
          <section className="review-column">
            <section className="panel report-panel">
              <div className="panel-header report-header"><div><div className="eyebrow">SOURCE REPORT</div><h2>{selectedId || 'Select a document'}</h2></div><div className="report-actions"><span className={`document-status-badge ${documentStatus.toLowerCase().replaceAll('_', '-')}`}>{documentStatus.replaceAll('_', ' ')}</span>{extracting && <span className="plain-status">Extracting…</span>}{reextracting && <span className="plain-status">Re-extracting…</span>}{loadingDetail && <span className="plain-status">Loading</span>}</div></div>
              <div className="report-content" id="source-report">{loadingDetail ? <div className="empty-state">Loading report…</div> : docText ? <pre>{mergedRanges.length ? <>{mergedRanges.map((range, index) => <span key={`before-${index}`}>{docText.slice(index === 0 ? 0 : mergedRanges[index - 1].end, range.start)}<mark id={index === 0 ? `evidence-offset-${activeRanges[0].start}` : undefined} className="source-highlight">{docText.slice(range.start, range.end)}</mark></span>)}{docText.slice(mergedRanges[mergedRanges.length - 1].end)}</> : docText}</pre> : <div className="empty-state">No document text is available from the document API.</div>}</div>
            </section>

            <section className="panel extraction-panel">
              <div className="panel-header extraction-header"><div><div className="eyebrow">STRUCTURED EXTRACTION</div><h2>Extracted fields</h2><p>Catalog-driven values and evidence</p></div><div className="extraction-header-actions"><span className="subtle-count">{fields.length} fields · {extractionCases.length} cases</span>{selectedId && fields.length > 0 && <><button className="review-action accept" onClick={() => saveCaseDecision('ACCEPTED')} disabled={reviewBusy}>Accept case</button><button className="review-action reject" onClick={() => saveCaseDecision('REJECTED')} disabled={reviewBusy}>Reject case</button><button className="review-action edit" onClick={beginCaseEdit} disabled={reviewBusy}>Edit case</button><button className="review-action" onClick={() => saveDocumentDecision('ACCEPTED')} disabled={reviewBusy}>Accept all</button><button className="review-action" onClick={() => saveDocumentDecision('REJECTED')} disabled={reviewBusy}>Reject all</button></>}{selectedId && documentStatus !== 'NOT_PROCESSED' && documentStatus !== 'IN_PROGRESS' && <button className="refresh-button reextract-button" onClick={reextractSelectedDocument} disabled={reextracting || extracting || loadingDetail}>{reextracting ? 'Re-extracting…' : '↻ Re-extract'}</button>}</div></div>
              {extractionCases.length > 1 && <div className="case-switcher">
                <div className="case-switcher-heading"><strong>Cases in this report</strong><span>{activeCaseIndex + 1} of {extractionCases.length}</span></div>
                <div className="case-tabs">{extractionCases.map((item: any, index: number) => {
                  const identity = item?.fields?.patient_initials?.value ?? item?.fields?.patient_id?.value ?? `Case ${index + 1}`
                  const decision = reviewData?.caseDecisions?.[String(index)]?.action
                  return <button key={index} className={`case-tab ${activeCaseIndex === index ? 'active' : ''}`} onClick={() => { setActiveCaseIndex(index); setActiveField('') }}>
                    <strong>Case {index + 1}</strong><span>{String(identity)}</span><small>{decision ?? (item?.caseValidation?.validCase ? 'Ready for review' : 'Review required')}</small>
                  </button>
                })}</div>
              </div>}
              {extractionCases.length === 1 && <div className="case-switcher single-case"><strong>Case 1</strong><span>{String(activeCase?.fields?.patient_initials?.value ?? 'Single patient case')}</span></div>}
              {reviewWarnings.length > 0 && (
                <div className="review-warnings">
                  <strong>Review required</strong>
                  <p>Some information is missing or needs attention. Available extracted information has been retained.</p>
                  <ul>
                    {reviewWarnings.map((warning: string, index: number) => (
                      <li key={index}>{warning}</li>
                    ))}
                  </ul>
                </div>
              )}
              {(reviewData?.documentDecision?.action || reviewMessage) && <div className={`document-review-state ${String(reviewData?.documentDecision?.action ?? '').toLowerCase()}`}>{reviewMessage || `Document review: ${reviewData.documentDecision.action.toLowerCase()}`}</div>}
              {!fields.length && !loadingDetail && <div className="empty-state extraction-empty">{documentStatus === 'NOT_PROCESSED' ? <>This document has not been processed yet.<br /><span>It will be extracted when selected.</span></> : documentStatus === 'FAILED' ? <>The previous extraction failed.<br /><span>See the extraction error above or retry when ready.</span></> : documentStatus === 'IN_PROGRESS' ? 'Extraction is currently in progress.' : documentStatus === 'DUPLICATE' ? 'This document matches previously processed content.' : documentStatus === 'COMPLETED' ? 'Extraction completed, but no case information could be identified in the report.' : 'No extracted fields are available for this document.'}</div>}
              {extractionError && <div className="extraction-error">{extractionError}</div>}
              <div className="field-groups">
                {Object.entries(groups).map(([group, items]) => <div className="field-group" key={group}>
                  <h3>{group}</h3>
                  {items.map(field => <><button key={field.id} className={`field-row ${activeField === field.id ? 'field-active' : ''}`} onClick={() => { setActiveField(activeField === field.id ? '' : field.id); setActiveEvidenceIndex(0) }}>
                    <span className="field-main"><strong>{field.label}</strong><small>{field.id}</small></span>
                    <span className="field-value">{field.value}</span>
                    <span className={`state-pill ${field.status}`}>{field.status}</span>
                    <span className="confidence">{field.confidence}</span>
                  </button>
                  {activeField === field.id && <div className="inline-evidence">
                    <div className="evidence-title">Source evidence</div>
                    {field.evidence?.length ? field.evidence.map((ev: any, i: number) => <button type="button" className={`evidence-quote ${activeField === field.id && activeEvidenceIndex === i ? 'evidence-quote-active' : ''}`} key={i} onClick={() => { setActiveField(field.id); setActiveEvidenceIndex(i) }}>“{ev.quote}”{Number.isInteger(ev.start) && Number.isInteger(ev.end) ? <small>Click to locate · Characters {ev.start}–{ev.end}</small> : <small>Source position unavailable · review quote</small>}</button>) : <p>No evidence quote available for this field.</p>}
                  </div>}
                  </>)}
                </div>)}
              </div>
            </section>
          </section>
        </div>
        {duplicateInfo && <div className="review-modal-backdrop"><section className="review-modal compact-modal" role="dialog" aria-modal="true"><header className="review-modal-header"><div><div className="eyebrow">DUPLICATE DETECTED</div><h2>This document already exists</h2><p>Matching document: <strong>{duplicateInfo.existingDocumentId}</strong></p></div></header><div className="review-modal-body"><p>{duplicateInfo.message ?? 'The uploaded document matches existing content.'} You can save a separate copy or cancel.</p></div><footer className="review-modal-footer"><button className="review-action" onClick={() => {setDuplicateInfo(null);setUploadFile(null)}}>Cancel upload</button><button className="review-action save" disabled={uploadBusy || !uploadFile} onClick={() => uploadFile && checkAndUploadDocument(uploadFile,true)}>{uploadBusy?'Saving…':'Save anyway'}</button></footer></section></div>}
        {catalogOpen && catalogDraft && <div className="review-modal-backdrop"><section className="review-modal catalog-modal" role="dialog" aria-modal="true"><header className="review-modal-header"><div><div className="eyebrow">FIELD CATALOG</div><h2>Edit catalog fields</h2><p>Manage field definitions using form controls. Changes apply to new extractions.</p></div><button className="modal-close" onClick={() => setCatalogOpen(false)}>×</button></header><div className="review-modal-body catalog-form-body"><div className="catalog-meta-grid"><label className="edit-field"><span>Catalog version</span><input value={catalogDraft.catalog_version ?? ''} onChange={e => setCatalogDraft(p => p ? ({...p, catalog_version:e.target.value}) : p)} /></label><label className="edit-field"><span>Description</span><input value={catalogDraft.description ?? ''} onChange={e => setCatalogDraft(p => p ? ({...p, description:e.target.value}) : p)} /></label></div><div className="catalog-fields-heading"><h3>Field definitions ({catalogDraft.fields?.length ?? 0})</h3><button className="review-action" onClick={addCatalogField}>+ Add field</button></div>{(catalogDraft.fields ?? []).map((field: any, index: number) => <section className="catalog-field-card" key={`${field.id}-${index}`}><div className="catalog-field-grid"><label className="edit-field"><span>Field ID</span><input value={field.id ?? ''} onChange={e => updateCatalogField(index,'id',e.target.value)} /></label><label className="edit-field"><span>Display label</span><input value={field.label ?? ''} onChange={e => updateCatalogField(index,'label',e.target.value)} /></label><label className="edit-field"><span>Section</span><select value={field.section ?? ''} onChange={e => updateCatalogField(index,'section',e.target.value)}>{(catalogDraft.sections ?? []).map((section: any) => <option key={section.id} value={section.id}>{section.label ?? section.id}</option>)}</select></label><label className="edit-field"><span>Data type</span><select value={field.type ?? 'string'} onChange={e => updateCatalogField(index,'type',e.target.value)}>{['string','integer','number','date','coded','coded_multi','string_list','boolean'].map(type => <option key={type} value={type}>{type}</option>)}</select></label><label className="edit-field catalog-guidance"><span>Extraction guidance</span><textarea value={field.guidance ?? ''} onChange={e => updateCatalogField(index,'guidance',e.target.value)} rows={2} /></label>{['coded','coded_multi'].includes(field.type) && <label className="edit-field catalog-guidance"><span>Allowed options (one per line: code | label)</span><textarea rows={3} value={(field.options ?? []).map((option: any) => `${option.code}|${option.label}`).join('\n')} onChange={e => updateCatalogOptions(index,e.target.value)} /></label>}</div><div className="catalog-field-footer"><span>{field.validation ? 'Validation rules configured' : 'No additional validation rules'}</span><button className="review-action reject" onClick={() => removeCatalogField(index)}>Remove field</button></div></section>)}</div><footer className="review-modal-footer"><button className="review-action" onClick={()=>setCatalogOpen(false)}>Cancel</button><button className="review-action save" disabled={catalogBusy || !(catalogDraft.fields?.length)} onClick={saveCatalogDraft}>{catalogBusy?'Saving…':'Save and activate catalog'}</button></footer></section></div>}
        {editOpen && editDraft && <div className="review-modal-backdrop" role="presentation" onMouseDown={e => { if (e.target === e.currentTarget) setEditOpen(false) }}>
          <section className="review-modal" role="dialog" aria-modal="true" aria-labelledby="full-edit-title">
            <header className="review-modal-header"><div><div className="eyebrow">DOCUMENT REVIEW</div><h2 id="full-edit-title">Edit Case {activeCaseIndex + 1}</h2><p>Original report remains visible alongside the editable case fields.</p></div><button className="modal-close" onClick={() => setEditOpen(false)} aria-label="Close">×</button></header>
            <div className="review-modal-body split-edit-body">
              <section className="edit-source-pane"><h3>Original source report</h3><pre>{docText || 'Original report text is unavailable.'}</pre></section>
              <section className="edit-form-pane">{(editDraft.cases ?? []).map((caseItem: any, caseIndex: number) => <section className="edit-case" key={caseIndex}>
                <h3>Case {activeCaseIndex + 1} · {caseItem?.fields?.patient_initials?.value ?? 'Patient details'}</h3>
                <div className="edit-fields-grid">{Object.entries(caseItem.fields ?? {}).map(([fieldId, fieldValue]: [string, any]) => <label className="edit-field" key={fieldId}><span>{String(fieldId).replaceAll('_', ' ')}</span><input value={fieldValue?.value == null ? '' : Array.isArray(fieldValue.value) ? fieldValue.value.join(', ') : String(fieldValue.value)} onChange={e => updateDraftField(caseIndex, fieldId, e.target.value)} placeholder="Not stated"/><small>{Array.isArray(fieldValue?.value) ? 'Comma-separated list' : 'Scalar value'} · {fieldValue?.status ?? 'unknown'}</small></label>)}</div>
              </section>)}</section>
            </div>
            <footer className="review-modal-footer"><button className="review-action" onClick={() => setEditOpen(false)} disabled={reviewBusy}>Cancel</button><button className="review-action save" onClick={() => saveCaseDecision('EDITED', editDraft.cases?.[0])} disabled={reviewBusy}>{reviewBusy ? 'Saving…' : 'Save case changes'}</button></footer>
          </section>
        </div>}
        <footer className="app-footer"><span>THERAGENX · Pharmacovigilance review</span><span>{catalog ? 'Field catalog loaded' : 'Catalog status unavailable'} · Designed for focused, evidence-led review</span></footer>
      </section>
    </main>
  )
}

export default App
