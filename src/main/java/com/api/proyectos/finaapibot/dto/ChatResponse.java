package com.api.proyectos.finaapibot.dto;

import lombok.Data;

@Data
public class ChatResponse {
    private String sql;

    public ChatResponse(String sql) {
        this.sql = sql;
    }
}
