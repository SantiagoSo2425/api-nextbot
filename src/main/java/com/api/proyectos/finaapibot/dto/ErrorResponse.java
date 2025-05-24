package com.api.proyectos.finaapibot.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ErrorResponse {
    private LocalDateTime timestamp;
    private int status;          // Código HTTP (ej: 400)
    private String error;        // Tipo (ej: "Bad Request")
    private String message;      // Descripción (ej: "API Key no válida")
    private String path;         // Endpoint (ej: "/api/chat")

    public ErrorResponse(int status, String error, String message, String path) {
        this.timestamp = LocalDateTime.now();
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
    }
}
