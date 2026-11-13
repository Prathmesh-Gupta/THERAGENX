package com.theragenx.theragenx_api.review;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/api/reviews")
public class ReviewController { private final ReviewStore store; public ReviewController(ReviewStore store){this.store=store;} @GetMapping("/{documentId}") public JsonNode get(@PathVariable String documentId){return store.load(documentId);} @PatchMapping("/{documentId}") public JsonNode patch(@PathVariable String documentId,@RequestBody JsonNode request){return store.update(documentId,request);} }
