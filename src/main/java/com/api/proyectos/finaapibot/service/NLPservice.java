package com.api.proyectos.finaapibot.service;

import dev.langchain4j.model.huggingface.HuggingFaceChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class NLPservice {
    @Value("${huggingface.api.key}")
    private String apiKey;

    private final DataSource dataSource;
    private final Map<String, String> queryCache = new HashMap<>();

    public NLPservice(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String answerFromDatabase(String userQuestion) {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals("hf_00000000000")) {
            throw new RuntimeException("No se ha configurado un token válido de HuggingFace. Por favor, define 'huggingface.api.key' en application.properties.");
        }

        if (queryCache.containsKey(userQuestion)) {
            return executeSQL(queryCache.get(userQuestion));
        }

        String sql;
        try {
            HuggingFaceChatModel model = HuggingFaceChatModel.builder()
                    .accessToken(apiKey.trim())
                    .modelId("mistralai/Mixtral-8x7B-Instruct-v0.1")
                    .timeout(Duration.ofSeconds(120))
                    .build();

            String tableContext = """
            La base de datos MySQL 'mi_basedatos' contiene las siguientes tablas:
            accounting_account_balances,
            accounting_accounts,
            accounting_configurations,
            accounting_movements,
            accounting_voucher_items,
            accounting_voucher_types,
            accounting_vouchers,
            api_access_tokens,
            billing_numberings,
            client_consumptions,
            client_subscriptions,
            company,
            company_areas,
            configurations,
            consolidated_retention_certificates,
            contact_accounts,
            contact_items_interests,
            contact_login_codes,
            contact_password_resets,
            contact_register_validation_codes,
            contact_relationships,
            contact_statements,
            contacts,
            contract_salary_history,
            costs_and_expenses,
            costs_and_expenses_categories,
            coupon_groups,
            coupon_redemptions,
            coupons,
            custom_fields,
            dining_tables,
            discounts,
            document_items,
            documents,
            documents_external_register_status,
            ecommerce_configurations,
            ecommerce_contact_us,
            ecommerce_contact_users,
            ecommerce_item_questions,
            ecommerce_items_quantity_by_users,
            ecommerce_legal_info,
            ecommerce_purchase_orders,
            ecommerce_shipping_options,
            ecommerce_shopping_chats,
            ecommerce_user_register_validations,
            electronic_billing_counters,
            electronic_documents_configurations,
            electronic_payroll_data,
            electronic_payroll_submissions,
            electronic_payroll_test_set,
            employee_contracts,
            employee_positions,
            employees,
            epayco_payments,
            fixed_asset_depreciations,
            fixed_assets,
            fixed_assets_groups,
            headquarter_warehouses,
            headquarters,
            integrations,
            inventory_adjustments,
            inventory_groups,
            item_balance,
            item_categories,
            item_depreciations,
            item_kardex,
            item_subcategories,
            item_variations,
            items,
            ledgers,
            mercado_pago_payments,
            migrations,
            notification_configurations,
            oauth_access_tokens,
            oauth_auth_codes,
            oauth_clients,
            oauth_personal_access_clients,
            oauth_refresh_tokens,
            opening_inventory_balances,
            opening_receivable_payable_balances,
            payment_conditions,
            payments,
            paynilo,
            paynilo_payments,
            payroll_configurations,
            payroll_consolidated,
            payroll_deductions,
            payroll_details,
            payroll_incomes,
            payroll_providers,
            payrolls,
            plan_electronic_documents,
            plan_restrictions,
            plan_system_controller,
            price_lists,
            radian_documents,
            radian_events,
            retention_concepts,
            retentions,
            retentions_applied,
            retentions_certificates,
            role_permissions,
            roles,
            severance_payments,
            system_counters,
            system_restrictions,
            taxes,
            template_versions,
            templates,
            term_and_conditions,
            user_data,
            user_headquarters,
            user_roles,
            values_x_item,
            warehouse_transfer_logs,
            warehouses.
            Utiliza solo estas tablas para generar las consultas.
            """;

            String prompt = """
Eres un asistente experto en SQL. Convierte la siguiente pregunta a una consulta SQL válida para MySQL.

Contexto de las tablas disponibles en la base de datos 'mi_basedatos':
%s

REGLAS IMPORTANTES:
- SOLO usa las tablas y columnas listadas arriba.
- NO inventes alias ni nombres de tablas/columnas.
- Si la pregunta es sobre empleados, usa la tabla 'employees'.
- Si es sobre facturas o ventas, usa la tabla 'documents' y el campo 'total_amount' y 'date'.
- Si es sobre clientes, usa la tabla 'contacts'.
- Si la pregunta es sobre ganancias del último mes, suma 'total_amount' de 'documents' donde 'type' sea 'invoice' y la fecha sea del último mes.
- NO uses acentos graves (backticks) salvo que sea necesario.
- SOLO responde con la consulta SQL, sin explicación.

EJEMPLOS:
Pregunta: '¿Cuántos empleados hay?'
SQL: SELECT COUNT(*) FROM employees;

Pregunta: 'Ganancias del último mes'
SQL: SELECT SUM(total_amount) AS ganancias_ultimo_mes FROM documents WHERE type = 'invoice' AND date >= DATE_SUB(CURDATE(), INTERVAL 1 MONTH);

Pregunta: 'Lista de clientes'
SQL: SELECT * FROM contacts;

Pregunta: 'Factura más alta'
SQL: SELECT * FROM documents WHERE type = 'invoice' ORDER BY total_amount DESC LIMIT 1;

Pregunta: '%s'
SQL:
""".formatted(tableContext, userQuestion);

            String iaResponse = model.generate(prompt).trim();
            sql = iaResponse.lines()
                    .filter(line -> line.matches("(?i)^(select|update|delete|insert|create|alter|drop|truncate|grant|revoke|with)\\b.*"))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("La IA no generó una consulta SQL válida. Respuesta: " + iaResponse));

            queryCache.put(userQuestion, sql);

        } catch (Exception e) {
            sql = getFallbackQuery(userQuestion);
            if (sql == null) {
                return "Lo siento, no pude generar una consulta SQL para esta pregunta. Error: " + e.getMessage();
            }
        }

        return executeSQL(sql);
    }

    private String getFallbackQuery(String question) {
        question = question.toLowerCase();

        if (question.contains("cuantos empleados") || question.contains("cuántos empleados") ||
                question.contains("cantidad de empleados") || question.contains("total de empleados")) {
            return "SELECT COUNT(*) FROM employees;";
        } else if (question.contains("todos los empleados") || question.contains("listar empleados")) {
            return "SELECT * FROM employees;";
        } else if ((question.contains("factura") && (question.contains("mayor") || question.contains("más alta"))) ||
                   (question.contains("factura") && question.contains("alta"))) {
            return "SELECT * FROM documents WHERE type = 'invoice' ORDER BY total_amount DESC LIMIT 1;";
        } else if (question.contains("total facturas") || question.contains("suma de facturas")) {
            return "SELECT SUM(total_amount) as total_facturado FROM documents WHERE type = 'invoice';";
        } else if ((question.contains("ganancias") || question.contains("ventas")) && question.contains("ultimo mes")) {
            return "SELECT SUM(total_amount) AS ganancias_ultimo_mes FROM documents WHERE type = 'invoice' AND date >= DATE_SUB(CURDATE(), INTERVAL 1 MONTH);";
        } else if (question.contains("lista de clientes") || question.contains("todos los clientes")) {
            return "SELECT * FROM contacts;";
        } else if (question.contains("salario") && question.contains("promedio")) {
            return "SELECT AVG(salary) as salario_promedio FROM employees;";
        }
        return null;
    }

    private String executeSQL(String sql) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            String dbUrl = conn.getMetaData().getURL();
            String dbUser = conn.getMetaData().getUserName();
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            String dbVersion = conn.getMetaData().getDatabaseProductVersion();

            System.out.println("========== INFORMACIÓN DE CONEXIÓN ==========");
            System.out.println("Base de datos: " + dbProduct + " " + dbVersion);
            System.out.println("URL de conexión: " + dbUrl);
            System.out.println("Usuario: " + dbUser);
            System.out.println("Driver: " + conn.getMetaData().getDriverName() + " " + conn.getMetaData().getDriverVersion());
            System.out.println("Catálogo actual: " + conn.getCatalog());
            System.out.println("===========================================");

            boolean isH2 = dbUrl.contains("h2:mem");
            boolean isMysql = dbUrl.contains("mysql");

            if (isH2) {
                System.out.println("⚠️ UTILIZANDO BASE DE DATOS H2 EN MEMORIA (MODO DESARROLLO)");
                createDevelopmentTables(conn);
                sql = adaptSqlForH2(sql);
            } else if (isMysql) {
                System.out.println("✅ UTILIZANDO BASE DE DATOS MYSQL");
            } else {
                System.err.println("❌ ¡ADVERTENCIA! No se detectó ni MySQL ni H2. Tipo de base de datos: " + dbProduct);
            }

            System.out.println("Consulta a ejecutar: " + sql);

            boolean isSelect = sql.trim().toLowerCase().startsWith("select");

            if (isSelect) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    StringBuilder result = new StringBuilder();
                    ResultSetMetaData meta = rs.getMetaData();
                    int columns = meta.getColumnCount();

                    for (int i = 1; i <= columns; i++) {
                        result.append(meta.getColumnName(i));
                        if (i < columns) result.append(" | ");
                    }
                    result.append("\n");

                    int rowCount = 0;
                    while (rs.next()) {
                        rowCount++;
                        for (int i = 1; i <= columns; i++) {
                            result.append(rs.getString(i) != null ? rs.getString(i) : "NULL");
                            if (i < columns) result.append(" | ");
                        }
                        result.append("\n");
                    }

                    System.out.println("Filas devueltas: " + rowCount);

                    if (rowCount == 0) {
                        return "La consulta SQL se ejecutó correctamente pero no devolvió resultados.\nConsulta: " + sql;
                    }
                    return result.toString();
                }
            } else {
                String operationType = sql.trim().split(" ")[0].toUpperCase();
                int affectedRows = stmt.executeUpdate(sql);
                return "Operación SQL (" + operationType + ") exitosa. Filas afectadas: " + affectedRows + ".\nConsulta: " + sql;
            }
        } catch (SQLException e) {
            System.err.println("Error SQL: " + e.getMessage());
            System.err.println("SQL State: " + e.getSQLState());
            System.err.println("Error Code: " + e.getErrorCode());
            if (e.getMessage().toLowerCase().contains("unknown table") || e.getMessage().toLowerCase().contains("unknown column")) {
                return "Error: La consulta hace referencia a una tabla o columna inexistente. Por favor, revisa la pregunta o usa solo las tablas y columnas disponibles.\nConsulta problemática: " + sql;
            }
            return "Error al ejecutar la consulta SQL: " + e.getMessage() + "\nConsulta problemática: " + sql;
        }
    }

    private String adaptSqlForH2(String sql) {
        sql = sql.replaceAll("(?i)MONTH\\(([^)]+)\\)", "EXTRACT(MONTH FROM $1)");
        return sql;
    }

    private void createDevelopmentTables(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS employees (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(100), " +
                    "position VARCHAR(100), " +
                    "salary DECIMAL(10,2), " +
                    "hire_date DATE)");

            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM employees");
            rs.next();
            if (rs.getInt(1) == 0) {
                stmt.execute("INSERT INTO employees (name, position, salary, hire_date) VALUES " +
                        "('Juan Pérez', 'Gerente', 5000.00, '2020-01-15'), " +
                        "('María López', 'Desarrollador', 3500.00, '2021-03-10'), " +
                        "('Carlos Rodríguez', 'Analista', 3200.00, '2019-11-05'), " +
                        "('Ana Gómez', 'Diseñador', 2800.00, '2022-02-20'), " +
                        "('Pedro Martínez', 'Vendedor', 2500.00, '2021-07-30'), " +
                        "('Laura Sánchez', 'Recursos Humanos', 3000.00, '2020-05-12'), " +
                        "('Roberto Díaz', 'Contador', 3800.00, '2019-09-18'), " +
                        "('Sofía Hernández', 'Marketing', 3200.00, '2021-01-25'), " +
                        "('Miguel Torres', 'Soporte Técnico', 2700.00, '2022-04-05'), " +
                        "('Carmen Flores', 'Administrativo', 2600.00, '2020-11-22')");
            }

            stmt.execute("CREATE TABLE IF NOT EXISTS documents (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "type VARCHAR(50), " +
                    "document_number VARCHAR(50), " +
                    "date DATE, " +
                    "contact_id INT, " +
                    "total_amount DECIMAL(12,2))");

            rs = stmt.executeQuery("SELECT COUNT(*) FROM documents");
            rs.next();
            if (rs.getInt(1) == 0) {
                stmt.execute("INSERT INTO documents (type, document_number, date, contact_id, total_amount) VALUES " +
                        "('invoice', 'INV-001', '2023-01-15', 1, 1500.00), " +
                        "('invoice', 'INV-002', '2023-01-20', 2, 2300.50), " +
                        "('invoice', 'INV-003', '2023-02-05', 1, 1800.75), " +
                        "('invoice', 'INV-004', '2023-02-18', 3, 950.25), " +
                        "('invoice', 'INV-005', '2023-03-10', 2, 3200.00)");
            }

            stmt.execute("CREATE TABLE IF NOT EXISTS contacts (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(100), " +
                    "email VARCHAR(100), " +
                    "phone VARCHAR(20), " +
                    "address VARCHAR(200))");

            rs = stmt.executeQuery("SELECT COUNT(*) FROM contacts");
            rs.next();
            if (rs.getInt(1) == 0) {
                stmt.execute("INSERT INTO contacts (name, email, phone, address) VALUES " +
                        "('Empresa ABC', 'contacto@abc.com', '555-1234', 'Calle Principal 123'), " +
                        "('Distribuidora XYZ', 'ventas@xyz.com', '555-5678', 'Av. Central 456'), " +
                        "('Servicios Técnicos', 'info@serv-tec.com', '555-9012', 'Plaza Mayor 789')");
            }

        } catch (SQLException e) {
            System.err.println("Error al crear tablas de desarrollo: " + e.getMessage());
        }
    }
}