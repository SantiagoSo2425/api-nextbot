package com.api.proyectos.finaapibot.controller;

import com.api.proyectos.finaapibot.service.NLPservice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final NLPservice nlpService;

    public ChatController(NLPservice nlpService) {
        this.nlpService = nlpService;
    }

    @PostMapping
    public ResponseEntity<String> getSQLFromQuestion(@RequestBody String question) {
        try {
            String result = nlpService.answerFromDatabase(question);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity
                    .internalServerError()
                    .body("Error al procesar la consulta: " + e.getMessage());
        }
    }
}
