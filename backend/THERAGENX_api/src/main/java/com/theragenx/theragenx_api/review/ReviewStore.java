package com.theragenx.theragenx_api.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.regex.Pattern;

@Service
public class ReviewStore {
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");
  private final ObjectMapper mapper; private final Path root;
  public ReviewStore(ObjectMapper mapper, @Value("${app.review.path:../../out/reviews}") String path) { this.mapper=mapper; this.root=Path.of(path).toAbsolutePath().normalize(); }
  public synchronized ObjectNode load(String id) { Path file=file(id); try { if(Files.exists(file)) return (ObjectNode)mapper.readTree(file.toFile()); } catch(IOException e){ throw new IllegalStateException("Unable to read review data for "+id,e); } ObjectNode empty=mapper.createObjectNode(); empty.put("documentId",id); empty.set("decisions",mapper.createObjectNode()); return empty; }
  public synchronized ObjectNode update(String id, JsonNode request) {
    ObjectNode data=load(id);
    String action=request.path("action").asText("").toUpperCase();
    if(!java.util.Set.of("ACCEPTED","REJECTED","EDITED").contains(action)) throw new IllegalArgumentException("action must be ACCEPTED, REJECTED or EDITED");
    String scope=request.path("scope").asText("");
    boolean documentScope="DOCUMENT".equalsIgnoreCase(scope) || (!request.has("caseIndex") && !request.has("fieldId"));
    boolean caseScope="CASE".equalsIgnoreCase(scope);
    ObjectNode decision=mapper.createObjectNode(); decision.put("action",action);
    if(caseScope) {
      int caseIndex=request.path("caseIndex").asInt(-1);
      if(caseIndex<0) throw new IllegalArgumentException("caseIndex is required for CASE scope");
      if("EDITED".equals(action)) {
        JsonNode edited=request.get("editedCase");
        if(edited==null || !edited.isObject()) throw new IllegalArgumentException("editedCase must be a case object");
        decision.set("editedCase",edited.deepCopy());
      }
      if(request.hasNonNull("reason")) decision.put("reason",request.path("reason").asText());
      decision.put("updatedAt",Instant.now().toString());
      ObjectNode caseDecisions=data.with("caseDecisions");
      caseDecisions.set(String.valueOf(caseIndex),decision);
    } else if(documentScope) {
      if("EDITED".equals(action)) { JsonNode edited=request.has("editedExtraction")?request.get("editedExtraction"):request.get("value"); if(edited==null || !edited.isObject()) throw new IllegalArgumentException("editedExtraction must be the complete extraction JSON object"); decision.set("editedExtraction",edited.deepCopy()); }
      if(request.hasNonNull("reason")) decision.put("reason",request.path("reason").asText());
      decision.put("updatedAt",Instant.now().toString()); data.set("documentDecision",decision);
    } else {
      int caseIndex=request.path("caseIndex").asInt(-1); String fieldId=request.path("fieldId").asText("");
      if(caseIndex<0 || !fieldId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("caseIndex or fieldId is invalid");
      if("EDITED".equals(action)) { if(!request.has("value")) throw new IllegalArgumentException("Edited value is required"); decision.set("reviewedValue",request.get("value")); }
      if(request.hasNonNull("reason")) decision.put("reason",request.path("reason").asText());
      decision.put("updatedAt",Instant.now().toString()); ((ObjectNode)data.path("decisions")).set(caseIndex+":"+fieldId,decision);
    }
    persist(id,data); return data;
  }
  private Path file(String id){ if(id==null || !SAFE_ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid document id"); return root.resolve(id+".json"); }
  private void persist(String id,ObjectNode data){ try { Files.createDirectories(root); Path target=file(id), temp=target.resolveSibling(target.getFileName()+".tmp"); mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),data); try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(IOException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);} }catch(IOException e){throw new IllegalStateException("Unable to save review data for "+id,e);} }
}
