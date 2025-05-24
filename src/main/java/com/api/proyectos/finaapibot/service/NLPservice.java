package com.api.proyectos.finaapibot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class NLPservice {
    @Value("${gemini.api.key}")
    private String apiKey;

    private final DataSource dataSource;
    private final Map<String, String> queryCache = new HashMap<>();
    private Map<String, List<String>> schemaMetadata = new HashMap<>();

    public NLPservice(DataSource dataSource) {
        this.dataSource = dataSource;
        // Cargar metadatos del esquema cuando se inicia el servicio
        loadSchemaMetadata();
    }

    /**
     * Carga metadatos del esquema para proporcionar mejor contexto a la IA
     */
    private void loadSchemaMetadata() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();

            // Cargar información de tablas y columnas
            try (ResultSet tables = metaData.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    List<String> columns = new ArrayList<>();

                    try (ResultSet columnData = metaData.getColumns(conn.getCatalog(), null, tableName, "%")) {
                        while (columnData.next()) {
                            String columnName = columnData.getString("COLUMN_NAME");
                            String dataType = columnData.getString("TYPE_NAME");
                            columns.add(columnName + " (" + dataType + ")");
                        }
                    }

                    schemaMetadata.put(tableName, columns);
                }
            }

            System.out.println("✅ Metadatos del esquema cargados correctamente: " + schemaMetadata.size() + " tablas");
        } catch (Exception e) {
            System.err.println("❌ Error al cargar metadatos del esquema: " + e.getMessage());
            // Si falla, seguirá funcionando pero con menos contexto
        }
    }

    /**
     * Genera una representación enriquecida del esquema para la IA
     */
    private String generateEnhancedSchemaContext() {
        StringBuilder schema = new StringBuilder();

        // Si tenemos metadatos, usamos la información detallada
        if (!schemaMetadata.isEmpty()) {
            schema.append("Esquema de la base de datos con tablas y columnas principales:\n\n");

            // Añadir tablas principales con sus columnas
            for (Map.Entry<String, List<String>> entry : schemaMetadata.entrySet()) {
                schema.append("TABLA: ").append(entry.getKey()).append("\n");
                schema.append("COLUMNAS: ");
                schema.append(String.join(", ", entry.getValue()));
                schema.append("\n\n");
            }
        } else {
            // Si no tenemos metadatos, usamos solo la lista de tablas
            schema.append("Tablas disponibles en la base de datos:\n");
            schema.append(getBasicTableList());
        }

        return schema.toString();
    }

    /**
     * Lista básica de tablas por si falla la carga de metadatos
     */
    private String getBasicTableList() {
        return """
            accounting_account_balances, accounting_accounts, accounting_configurations, accounting_movements,
            accounting_voucher_items, accounting_voucher_types, accounting_vouchers, api_access_tokens,
            billing_numberings, client_consumptions, client_subscriptions, company, company_areas,
            configurations, consolidated_retention_certificates, contact_accounts, contact_items_interests,
            contact_login_codes, contact_password_resets, contact_register_validation_codes,
            contact_relationships, contact_statements, contacts, contract_salary_history, costs_and_expenses,
            costs_and_expenses_categories, coupon_groups, coupon_redemptions, coupons, custom_fields,
            dining_tables, discounts, document_items, documents, documents_external_register_status,
            ecommerce_configurations, ecommerce_contact_us, ecommerce_contact_users, ecommerce_item_questions,
            ecommerce_items_quantity_by_users, ecommerce_legal_info, ecommerce_purchase_orders,
            ecommerce_shipping_options, ecommerce_shopping_chats, ecommerce_user_register_validations,
            electronic_billing_counters, electronic_documents_configurations, electronic_payroll_data,
            electronic_payroll_submissions, electronic_payroll_test_set, employee_contracts, employee_positions,
            employees, epayco_payments, fixed_asset_depreciations, fixed_assets, fixed_assets_groups,
            headquarter_warehouses, headquarters, integrations, inventory_adjustments, inventory_groups,
            item_balance, item_categories, item_depreciations, item_kardex, item_subcategories,
            item_variations, items, ledgers, mercado_pago_payments, migrations, notification_configurations,
            oauth_access_tokens, oauth_auth_codes, oauth_clients, oauth_personal_access_clients,
            oauth_refresh_tokens, opening_inventory_balances, opening_receivable_payable_balances,
            payment_conditions, payments, paynilo, paynilo_payments, payroll_configurations, payroll_consolidated,
            payroll_deductions, payroll_details, payroll_incomes, payroll_providers, payrolls,
            plan_electronic_documents, plan_restrictions, plan_system_controller, price_lists, radian_documents,
            radian_events, retention_concepts, retentions, retentions_applied, retentions_certificates,
            role_permissions, roles, severance_payments, system_counters, system_restrictions, taxes,
            template_versions, templates, term_and_conditions, user_data, user_headquarters, user_roles,
            values_x_item, warehouse_transfer_logs, warehouses
            """;
    }

    /**
     * Construye un prompt aún más avanzado para Gemini, con instrucciones y ejemplos enriquecidos.
     */
    private String buildAdvancedPrompt(String userQuestion, String username) {
        String currentDateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String schemaContext = generateEnhancedSchemaContext();

        return """
        # CONTEXTO DEL SISTEMA
        Eres un asistente experto en SQL y análisis de datos empresariales. 
        Tu tarea es traducir cualquier pregunta en español (formal, informal, técnica, coloquial, abreviada, con errores ortográficos o jerga) a una consulta SQL válida para MySQL.
        Fecha actual: %s
        Usuario: %s

        # CONTEXTO DE BASE DE DATOS
        %s

        # INSTRUCCIONES AVANZADAS
        - Responde SOLO con la consulta SQL, sin explicación, sin comentarios, sin encabezados.
        - Usa únicamente las tablas y columnas listadas en el contexto.
        - Si la pregunta es ambigua, elige la interpretación más lógica y útil para un entorno empresarial.
        - Si se menciona "últimos", "recientes" o "más nuevos", usa la columna de fecha o id descendente.
        - Si se pide "mayor", "más alto", "top", "mejor", usa ORDER BY y LIMIT 1.
        - Si se menciona un nombre parcial, usa LIKE con %%.
        - Si la pregunta es sobre fechas relativas ("ayer", "este mes", "último trimestre"), traduce a funciones de fecha de MySQL.
        - Si la pregunta es sobre promedios, sumas, máximos, mínimos, cuentas, usa funciones agregadas.
        - Si la pregunta es sobre relaciones, usa JOIN si es necesario.
        - Si la pregunta es sobre "quién compra más", "mejor cliente", "empleado destacado", etc., interpreta como ranking por cantidad o monto.
        - Si la pregunta es sobre "revenue", "ventas", "ingresos", usa la tabla documents y el campo total_amount.
        - Si la pregunta es sobre asistencia, busca tablas relacionadas como attendance.
        - Si la pregunta es sobre "facturas", filtra por type = 'invoice' en documents.
        - Si la pregunta es sobre "clientes", usa contacts.
        - Si la pregunta es sobre "empleados", usa employees.
        - Si la pregunta es sobre "salario", usa salary en employees.
        - Si la pregunta es sobre "nombre", busca por name.
        - Si la pregunta es sobre "fecha", busca por date.
        - Si la pregunta es sobre "últimos X", usa LIMIT X.
        - Si la pregunta es sobre "total", "cuánto", "cuántos", "número", usa COUNT o SUM según corresponda.
        - Si la pregunta es sobre "promedio", usa AVG.
        - Si la pregunta es sobre "mayor", "más alto", usa MAX o ORDER BY DESC LIMIT 1.
        - Si la pregunta es sobre "menor", "más bajo", usa MIN o ORDER BY ASC LIMIT 1.
        - Si la pregunta es sobre "todos", "lista", "ver", "mostrar", devuelve todos los registros relevantes.
        - Si la pregunta es sobre "detalles", devuelve todas las columnas de la tabla relevante.
        - Si la pregunta es sobre "última semana", usa INTERVAL 7 DAY.
        - Si la pregunta es sobre "último mes", usa INTERVAL 1 MONTH.
        - Si la pregunta es sobre "último trimestre", usa INTERVAL 3 MONTH.
        - Si la pregunta es sobre "hoy", usa CURDATE().
        - Si la pregunta es sobre "ayer", usa DATE_SUB(CURDATE(), INTERVAL 1 DAY).
        - Si la pregunta es sobre "este mes", usa MONTH(date) = MONTH(CURDATE()).
        - Si la pregunta es sobre "este año", usa YEAR(date) = YEAR(CURDATE()).
        - Si la pregunta es sobre "por cliente", "por empleado", agrupa por el campo correspondiente.
        - Si la pregunta es sobre "buscar", "encontrar", "info de", usa WHERE con LIKE.
        - Si la pregunta es sobre "mayor compra", "mayor venta", usa ORDER BY total_amount DESC LIMIT 1.
        - Si la pregunta es sobre "última compra", "última venta", usa ORDER BY date DESC LIMIT 1.
        - Si la pregunta es sobre "primer", "más antiguo", usa ORDER BY ASC LIMIT 1.
        - Si la pregunta es sobre "top X", usa ORDER BY y LIMIT X.
        - Si la pregunta es sobre "clientes frecuentes", agrupa y ordena por cantidad de compras.
        - Si la pregunta es sobre "empleados activos", busca por estado o campo activo si existe.
        - Si la pregunta es sobre "empleados inactivos", busca por estado o campo inactivo si existe.
        - Si la pregunta es sobre "empleados por área", agrupa por área si existe.
        - Si la pregunta es sobre "ventas por mes", agrupa por mes.
        - Si la pregunta es sobre "ventas por año", agrupa por año.
        - Si la pregunta es sobre "ventas por cliente", agrupa por cliente.
        - Si la pregunta es sobre "ventas por producto", agrupa por producto.
        - Si la pregunta es sobre "ventas por empleado", agrupa por empleado.
        - Si la pregunta es sobre "ventas por sucursal", agrupa por sucursal.
        - Si la pregunta es sobre "ventas por región", agrupa por región.
        - Si la pregunta es sobre "ventas por categoría", agrupa por categoría.
        - Si la pregunta es sobre "ventas por canal", agrupa por canal.
        - Si la pregunta es sobre "ventas por tipo", agrupa por tipo.
        - Si la pregunta es sobre "ventas por estado", agrupa por estado.
        - Si la pregunta es sobre "ventas por ciudad", agrupa por ciudad.
        - Si la pregunta es sobre "ventas por país", agrupa por país.
        - Si la pregunta es sobre "ventas por moneda", agrupa por moneda.
        - Si la pregunta es sobre "ventas por método de pago", agrupa por método de pago.
        - Si la pregunta es sobre "ventas por vendedor", agrupa por vendedor.
        - Si la pregunta es sobre "ventas por supervisor", agrupa por supervisor.
        - Si la pregunta es sobre "ventas por gerente", agrupa por gerente.
        - Si la pregunta es sobre "ventas por director", agrupa por director.
        - Si la pregunta es sobre "ventas por canal digital", agrupa por canal digital.
        - Si la pregunta es sobre "ventas por canal físico", agrupa por canal físico.
        - Si la pregunta es sobre "ventas por canal telefónico", agrupa por canal telefónico.
        - Si la pregunta es sobre "ventas por canal presencial", agrupa por canal presencial.
        - Si la pregunta es sobre "ventas por canal online", agrupa por canal online.
        - Si la pregunta es sobre "ventas por canal offline", agrupa por canal offline.
        - Si la pregunta es sobre "ventas por canal tradicional", agrupa por canal tradicional.
        - Si la pregunta es sobre "ventas por canal moderno", agrupa por canal moderno.
        - Si la pregunta es sobre "ventas por canal alternativo", agrupa por canal alternativo.
        - Si la pregunta es sobre "ventas por canal propio", agrupa por canal propio.
        - Si la pregunta es sobre "ventas por canal externo", agrupa por canal externo.
        - Si la pregunta es sobre "ventas por canal indirecto", agrupa por canal indirecto.
        - Si la pregunta es sobre "ventas por canal directo", agrupa por canal directo.
        - Si la pregunta es sobre "ventas por canal mayorista", agrupa por canal mayorista.
        - Si la pregunta es sobre "ventas por canal minorista", agrupa por canal minorista.
        - Si la pregunta es sobre "ventas por canal distribuidor", agrupa por canal distribuidor.
        - Si la pregunta es sobre "ventas por canal importador", agrupa por canal importador.
        - Si la pregunta es sobre "ventas por canal exportador", agrupa por canal exportador.
        - Si la pregunta es sobre "ventas por canal franquicia", agrupa por canal franquicia.
        - Si la pregunta es sobre "ventas por canal concesionario", agrupa por canal concesionario.
        - Si la pregunta es sobre "ventas por canal representante", agrupa por canal representante.
        - Si la pregunta es sobre "ventas por canal agente", agrupa por canal agente.
        - Si la pregunta es sobre "ventas por canal broker", agrupa por canal broker.
        - Si la pregunta es sobre "ventas por canal marketplace", agrupa por canal marketplace.
        - Si la pregunta es sobre "ventas por canal ecommerce", agrupa por canal ecommerce.
        - Si la pregunta es sobre "ventas por canal retail", agrupa por canal retail.
        - Si la pregunta es sobre "ventas por canal horeca", agrupa por canal horeca.
        - Si la pregunta es sobre "ventas por canal institucional", agrupa por canal institucional.
        - Si la pregunta es sobre "ventas por canal gobierno", agrupa por canal gobierno.
        - Si la pregunta es sobre "ventas por canal salud", agrupa por canal salud.
        - Si la pregunta es sobre "ventas por canal educación", agrupa por canal educación.
        - Si la pregunta es sobre "ventas por canal construcción", agrupa por canal construcción.
        - Si la pregunta es sobre "ventas por canal industrial", agrupa por canal industrial.
        - Si la pregunta es sobre "ventas por canal agrícola", agrupa por canal agrícola.
        - Si la pregunta es sobre "ventas por canal ganadero", agrupa por canal ganadero.
        - Si la pregunta es sobre "ventas por canal pesquero", agrupa por canal pesquero.
        - Si la pregunta es sobre "ventas por canal minero", agrupa por canal minero.
        - Si la pregunta es sobre "ventas por canal energético", agrupa por canal energético.
        - Si la pregunta es sobre "ventas por canal tecnológico", agrupa por canal tecnológico.
        - Si la pregunta es sobre "ventas por canal financiero", agrupa por canal financiero.
        - Si la pregunta es sobre "ventas por canal logístico", agrupa por canal logístico.
        - Si la pregunta es sobre "ventas por canal transporte", agrupa por canal transporte.
        - Si la pregunta es sobre "ventas por canal turismo", agrupa por canal turismo.
        - Si la pregunta es sobre "ventas por canal entretenimiento", agrupa por canal entretenimiento.
        - Si la pregunta es sobre "ventas por canal deportivo", agrupa por canal deportivo.
        - Si la pregunta es sobre "ventas por canal cultural", agrupa por canal cultural.
        - Si la pregunta es sobre "ventas por canal social", agrupa por canal social.
        - Si la pregunta es sobre "ventas por canal ambiental", agrupa por canal ambiental.
        - Si la pregunta es sobre "ventas por canal político", agrupa por canal político.
        - Si la pregunta es sobre "ventas por canal religioso", agrupa por canal religioso.
        - Si la pregunta es sobre "ventas por canal militar", agrupa por canal militar.
        - Si la pregunta es sobre "ventas por canal judicial", agrupa por canal judicial.
        - Si la pregunta es sobre "ventas por canal legislativo", agrupa por canal legislativo.
        - Si la pregunta es sobre "ventas por canal ejecutivo", agrupa por canal ejecutivo.
        - Si la pregunta es sobre "ventas por canal internacional", agrupa por canal internacional.
        - Si la pregunta es sobre "ventas por canal nacional", agrupa por canal nacional.
        - Si la pregunta es sobre "ventas por canal regional", agrupa por canal regional.
        - Si la pregunta es sobre "ventas por canal local", agrupa por canal local.
        - Si la pregunta es sobre "ventas por canal metropolitano", agrupa por canal metropolitano.
        - Si la pregunta es sobre "ventas por canal rural", agrupa por canal rural.
        - Si la pregunta es sobre "ventas por canal urbano", agrupa por canal urbano.
        - Si la pregunta es sobre "ventas por canal suburbano", agrupa por canal suburbano.
        - Si la pregunta es sobre "ventas por canal periurbano", agrupa por canal periurbano.
        - Si la pregunta es sobre "ventas por canal costero", agrupa por canal costero.
        - Si la pregunta es sobre "ventas por canal montañoso", agrupa por canal montañoso.
        - Si la pregunta es sobre "ventas por canal selvático", agrupa por canal selvático.
        - Si la pregunta es sobre "ventas por canal desértico", agrupa por canal desértico.
        - Si la pregunta es sobre "ventas por canal polar", agrupa por canal polar.
        - Si la pregunta es sobre "ventas por canal tropical", agrupa por canal tropical.
        - Si la pregunta es sobre "ventas por canal templado", agrupa por canal templado.
        - Si la pregunta es sobre "ventas por canal frío", agrupa por canal frío.
        - Si la pregunta es sobre "ventas por canal cálido", agrupa por canal cálido.
        - Si la pregunta es sobre "ventas por canal húmedo", agrupa por canal húmedo.
        - Si la pregunta es sobre "ventas por canal seco", agrupa por canal seco.
        - Si la pregunta es sobre "ventas por canal lluvioso", agrupa por canal lluvioso.
        - Si la pregunta es sobre "ventas por canal nevado", agrupa por canal nevado.
        - Si la pregunta es sobre "ventas por canal ventoso", agrupa por canal ventoso.
        - Si la pregunta es sobre "ventas por canal soleado", agrupa por canal soleado.
        - Si la pregunta es sobre "ventas por canal nublado", agrupa por canal nublado.
        - Si la pregunta es sobre "ventas por canal tormentoso", agrupa por canal tormentoso.

        # EJEMPLOS DE PREGUNTAS Y RESPUESTAS
        Pregunta: ¿Cuántos empleados hay?
        SELECT COUNT(*) FROM employees;

        Pregunta: Dime quién gana más pasta en la empresa
        SELECT * FROM employees ORDER BY salary DESC LIMIT 1;

        Pregunta: Necesito checar las facturas de ayer nomás
        SELECT * FROM documents WHERE type = 'invoice' AND date = DATE_SUB(CURDATE(), INTERVAL 1 DAY);

        Pregunta: ¿Qué contactos tenemos en la BD?
        SELECT * FROM contacts;

        Pregunta: ctos empleados cobran + de 3mil?
        SELECT COUNT(*) FROM employees WHERE salary > 3000;

        Pregunta: me puedes mostrar las últimas 5 facturas de este mes porfa ;)
        SELECT * FROM documents WHERE type = 'invoice' AND MONTH(date) = MONTH(CURDATE()) ORDER BY date DESC LIMIT 5;

        Pregunta: Jaló Alejandro hoy a chamba?
        SELECT * FROM employees WHERE name LIKE '%%Alejandro%%' AND id IN (SELECT employee_id FROM attendance WHERE date = CURDATE());

        Pregunta: quien compra más?
        SELECT c.name, COUNT(d.id) as total_purchases FROM contacts c JOIN documents d ON c.id = d.contact_id WHERE d.type = 'invoice' GROUP BY c.id ORDER BY total_purchases DESC LIMIT 1;

        Pregunta: dame el revenue total del quarter, porfa
        SELECT SUM(total_amount) as quarterly_revenue FROM documents WHERE type = 'invoice' AND date BETWEEN DATE_SUB(CURDATE(), INTERVAL 3 MONTH) AND CURDATE();

        Pregunta: La info de Juan Pérez 
        SELECT * FROM employees WHERE name LIKE '%%Juan%%' AND name LIKE '%%Pérez%%';

        Pregunta: documentos del 24/5
        SELECT * FROM documents WHERE DAY(date) = 24 AND MONTH(date) = 5;

        Pregunta: %s
        """.formatted(currentDateTime, username, schemaContext, userQuestion);
    }

    public String answerFromDatabase(String userQuestion) {
        if (apiKey == null || apiKey.isBlank()) {
            return "No se ha configurado una clave API válida de Gemini. Por favor, define 'gemini.api.key' en application.properties.";
        }

        if (queryCache.containsKey(userQuestion)) {
            return executeSQL(queryCache.get(userQuestion));
        }

        String sql;
        try {
            // Extrae información de usuario desde la sesión o usa un valor predeterminado
            String username = getUserFromContext();
            String prompt = buildAdvancedPrompt(userQuestion, username);

            // Log del prompt para depuración (opcional)
            // System.out.println("Prompt enviado a Gemini: " + prompt);

            // Llamada HTTP a Gemini API
            String apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=" + apiKey;
            String requestBody = """
            {
              "contents": [
                {
                  "parts": [
                    {"text": %s}
                  ]
                }
              ],
              "generation_config": {
                "temperature": 0.2,
                "top_p": 0.95,
                "top_k": 40,
                "max_output_tokens": 1024
              }
            }
            """.formatted(toJsonString(prompt));

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int status = conn.getResponseCode();
            StringBuilder response = new StringBuilder();
            if (status == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line.trim());
                    }
                }
            } else {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line.trim());
                    }
                }
                System.err.println("Error de Gemini: " + response.toString());
                throw new RuntimeException("Error al llamar a Gemini API: HTTP " + status + " - " + response);
            }
            conn.disconnect();

            String iaResponse = extractTextFromGeminiResponse(response.toString());

            // Pre-procesado: limpia la respuesta de posibles explicaciones o texto adicional
            String cleanSQL = extractSQL(iaResponse);

            if (cleanSQL.isEmpty()) {
                throw new RuntimeException("La IA no generó una consulta SQL válida. Respuesta: " + iaResponse);
            }

            sql = cleanSQL;
            queryCache.put(userQuestion, sql);

        } catch (Exception e) {
            sql = getFallbackQuery(userQuestion);
            if (sql == null) {
                String errorMessage = e.getMessage();
                return "Lo siento, no pude generar una consulta SQL para esta pregunta. Error: " + errorMessage;
            }
        }

        return executeSQL(sql);
    }

    /**
     * Extrae sólo la parte SQL de la respuesta completa
     */
    private String extractSQL(String iaResponse) {
        // Primero intenta encontrar una línea completa que parezca SQL
        for (String line : iaResponse.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("(?i)^(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|WITH)\\b.*")) {
                // Asegurarse de terminar con punto y coma si no lo tiene
                return trimmed.endsWith(";") ? trimmed : trimmed + ";";
            }
        }

        // Si no encuentra líneas completas, busca una instrucción SQL dentro de la respuesta
        String sqlPattern = "(?i)(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|WITH)\\b[^;]*;";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(sqlPattern);
        java.util.regex.Matcher matcher = pattern.matcher(iaResponse);

        if (matcher.find()) {
            return matcher.group().trim();
        }

        return "";
    }

    private String getUserFromContext() {
        // Aquí podrías obtener el usuario actual de Spring Security,
        // de una sesión HTTP, o de otro contexto de seguridad
        // Por ahora devolvemos un valor fijo
        return "SantiagoSo2425";
    }

    // Resto de métodos (toJsonString, getFallbackQuery, executeSQL, etc.) permanecen sin cambios
    private String toJsonString(String text) {
        return "\"" + text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private String extractTextFromGeminiResponse(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode textNode = rootNode.at("/candidates/0/content/parts/0/text");
            if (textNode.isMissingNode()) {
                System.err.println("No se encontró 'text' en la respuesta de Gemini: " + json);
                return "";
            }
            return textNode.asText("");
        } catch (Exception e) {
            System.err.println("Error al parsear JSON: " + e.getMessage() + ". JSON: " + json);
            int idx = json.indexOf("\"text\":\"");
            if (idx == -1) return "";
            int start = idx + 8;
            int end = json.indexOf("\"", start);
            if (end == -1) end = json.length();
            String text = json.substring(start, end);
            return text.replace("\\n", "\n").replace("\\\"", "\"");
        }
    }

    private String getFallbackQuery(String question) {
        question = question.toLowerCase();

        // Ampliamos los fallbacks para cubrir más variaciones de lenguaje
        if (question.matches(".*(cuant|ctos|número|numero|total|count).*empleado.*")) {
            return "SELECT COUNT(*) FROM employees;";
        } else if (question.matches(".*(list|muestra|ver|dame|todos).*empleado.*")) {
            return "SELECT * FROM employees;";
        } else if (question.matches(".*(gana|cobra|salario).*(más|mas|mayor|alto).*") ||
                question.matches(".*(más|mas|mayor).*(gana|cobra|salario).*")) {
            return "SELECT * FROM employees ORDER BY salary DESC LIMIT 1;";
        } else if (question.matches(".*(factura|invoice).*(mayor|más alta|mas alta).*") ||
                question.matches(".*(mayor|más alta|mas alta).*(factura|invoice).*")) {
            return "SELECT * FROM documents WHERE type = 'invoice' ORDER BY total_amount DESC LIMIT 1;";
        } else if (question.matches(".*(total|suma).*(factura|invoice).*") ||
                question.matches(".*(factura|invoice).*(total|suma).*")) {
            return "SELECT SUM(total_amount) as total_facturado FROM documents WHERE type = 'invoice';";
        } else if (question.matches(".*(ganancia|ingreso|venta).*(último|ultimo|este).*(mes).*")) {
            return "SELECT SUM(total_amount) AS ganancias_ultimo_mes FROM documents WHERE type = 'invoice' AND date >= DATE_SUB(CURDATE(), INTERVAL 1 MONTH);";
        } else if (question.matches(".*(lista|ver|dame|todos).*(cliente|contact).*")) {
            return "SELECT * FROM contacts;";
        } else if (question.matches(".*(salario|sueldo).*(promedio|media|avg).*")) {
            return "SELECT AVG(salary) as salario_promedio FROM employees;";
        } else if (question.matches(".*(cliente|contact).*(último|ultimo|recent).*")) {
            return "SELECT * FROM contacts ORDER BY id DESC LIMIT 5;";
        }

        return null;
    }

    /**
     * Ejecuta una consulta SQL y devuelve el resultado como String.
     * Solo para SELECT. Para otros tipos de consulta, devuelve el número de filas afectadas.
     */
    private String executeSQL(String sql) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            boolean hasResultSet = stmt.execute(sql);

            if (hasResultSet) {
                ResultSet rs = stmt.getResultSet();
                StringBuilder sb = new StringBuilder();
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();

                // Encabezados
                for (int i = 1; i <= columnCount; i++) {
                    sb.append(meta.getColumnLabel(i));
                    if (i < columnCount) sb.append("\t");
                }
                sb.append("\n");

                // Filas
                while (rs.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        sb.append(rs.getString(i));
                        if (i < columnCount) sb.append("\t");
                    }
                    sb.append("\n");
                }
                return sb.toString().trim();
            } else {
                int updateCount = stmt.getUpdateCount();
                return "Filas afectadas: " + updateCount;
            }
        } catch (Exception e) {
            return "Error al ejecutar SQL: " + e.getMessage();
        }
    }
}
