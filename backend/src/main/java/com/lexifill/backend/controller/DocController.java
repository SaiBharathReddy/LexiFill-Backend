package com.lexifill.backend.controller;

import com.lexifill.backend.service.HuggingFaceService;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@CrossOrigin(origins = {"*"})
@RestController
@RequestMapping("/api")
public class DocController {

    @Autowired
    private HuggingFaceService huggingFaceService;

    private final Map<String, byte[]> uploadedFiles = new HashMap<>();
    private final Map<String, String> uploadedFileNames = new HashMap<>();

    // Upload DOCX and parse full text
//    @PostMapping("/parse")
//    public ResponseEntity<Map<String, Object>> parseDocx(@RequestParam("file") MultipartFile file) {
//        try {
//            byte[] fileBytes = file.getBytes();
//            String fileId = UUID.randomUUID().toString();
//            uploadedFiles.put(fileId, fileBytes);
//            uploadedFileNames.put(fileId, file.getOriginalFilename());
//
//            XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes));
//
//            StringBuilder fullText = new StringBuilder();
//            for (XWPFParagraph para : document.getParagraphs()) {
//                fullText.append(para.getText()).append("\n");
//            }
//            document.close();
//
//            // Ask LLM to extract contextual placeholders
//            List<Map<String, String>> placeholders = huggingFaceService.extractPlaceholders(fullText.toString());
//
//            Map<String, Object> response = new HashMap<>();
//            response.put("fileId", fileId);
//            response.put("fileName", file.getOriginalFilename());
//            response.put("placeholders", placeholders); // each has placeholder, context, question
//            response.put("text", fullText.toString());
//
//            return ResponseEntity.ok(response);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
//        }
//    }
    @GetMapping("/health")
    public String getHealth(){
        return "OK";
    }
    @PostMapping("/parse")
    public ResponseEntity<Map<String, Object>> parseDocx(@RequestParam("file") MultipartFile file) {
        try {
            byte[] fileBytes = file.getBytes();
            String fileId = UUID.randomUUID().toString();
            uploadedFiles.put(fileId, fileBytes);
            uploadedFileNames.put(fileId, file.getOriginalFilename());

            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes));
                 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {

                // Extract and normalize text
                String fullText = extractor.getText().replaceAll("\\s+", " ").trim();

                // Send to LLM
                List<Map<String, String>> placeholders = huggingFaceService.extractPlaceholders(fullText);

                Map<String, Object> response = new HashMap<>();
                response.put("fileId", fileId);
                response.put("fileName", file.getOriginalFilename());
                response.put("placeholders", placeholders);
                response.put("text", fullText);

                return ResponseEntity.ok(response);
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }


    // Fill placeholders using context-aware replacement
    @PostMapping("/fill")
    public ResponseEntity<byte[]> fillDoc(@RequestBody Map<String, Object> payload) {
        System.out.println(payload);
        try {
            String fileId = (String) payload.get("fileId");
            Map<String, String> answers = (Map<String, String>) payload.get("answers");
            List<Map<String, String>> placeholders = (List<Map<String, String>>) payload.get("placeholders");

            byte[] fileBytes = uploadedFiles.get(fileId);
            if (fileBytes == null) throw new RuntimeException("File not found");

            XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes));

            //Fill everywhere
            fillParagraphs(document.getParagraphs(), placeholders, answers);

            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        fillParagraphs(cell.getParagraphs(), placeholders, answers);
                    }
                }
            }

            for (XWPFHeader header : document.getHeaderList()) {
                fillParagraphs(header.getParagraphs(), placeholders, answers);
            }

            for (XWPFFooter footer : document.getFooterList()) {
                fillParagraphs(footer.getParagraphs(), placeholders, answers);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            document.close();

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("Content-Disposition", "attachment; filename=filled_" + uploadedFileNames.get(fileId))
                    .body(out.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(null);
        }
    }

    /** Helper method to fill placeholders safely */
    private void fillParagraphs(List<XWPFParagraph> paragraphs, List<Map<String, String>> placeholders, Map<String, String> answers) {
        for (XWPFParagraph para : paragraphs) {
            List<XWPFRun> runs = para.getRuns();
            if (runs == null || runs.isEmpty()) continue;

            // Combine all runs' text
            StringBuilder fullTextBuilder = new StringBuilder();
            for (XWPFRun run : runs) {
                String text = run.getText(0);
                if (text != null) fullTextBuilder.append(text);
            }
            String fullText = fullTextBuilder.toString();

            // Replace placeholders (case-insensitive)
            for (Map<String, String> ph : placeholders) {
                String placeholder = ph.get("placeholder");
                String key = ph.get("key");
                String answer = answers.get(key);
                System.out.println("***********"+placeholder+" "+key+"******************");

                if (answer != null) {
                    fullText = fullText.replaceAll("(?i)" + java.util.regex.Pattern.quote(placeholder), answer);
                }
            }

            // Clear existing runs
            for (int i = runs.size() - 1; i >= 0; i--) {
                para.removeRun(i);
            }

            // Add single run with updated text
            XWPFRun newRun = para.createRun();
            newRun.setText(fullText);
        }
    }

}
