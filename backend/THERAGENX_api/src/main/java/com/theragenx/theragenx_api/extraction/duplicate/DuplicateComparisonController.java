package com.theragenx.theragenx_api.extraction.duplicate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
@RestController @RequestMapping("/api/extractions")
public class DuplicateComparisonController { private final CaseDuplicateDetector detector; private final ObjectMapper mapper; public DuplicateComparisonController(CaseDuplicateDetector detector,ObjectMapper mapper){this.detector=detector;this.mapper=mapper;} @PostMapping("/{documentId}/duplicates") public JsonNode compare(@PathVariable String documentId,@RequestBody JsonNode body){ List<JsonNode> cases=new ArrayList<>(); JsonNode array=body.path("cases"); if(array.isArray()) array.forEach(cases::add); ObjectNode result=mapper.createObjectNode(); ArrayNode matches=mapper.createArrayNode(); detector.findMatches(cases,documentId).forEach(matches::add); result.set("matches",matches); return result; } }
