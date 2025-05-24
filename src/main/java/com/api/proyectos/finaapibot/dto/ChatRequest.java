package com.api.proyectos.finaapibot.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {
    @NotBlank(message = "La pregunta no puede estar vacía")
    private String pregunta;  // Ej: "¿Cuánto facturé en enero?"

    private String tenantId;  // (Opcional) ID de empresa para multi-tenancy

    public String getPregunta() {
        return pregunta;
    }

    public void setPregunta(String pregunta) {
        this.pregunta = pregunta;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
