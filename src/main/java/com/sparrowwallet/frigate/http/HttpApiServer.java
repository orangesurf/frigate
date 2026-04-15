package com.sparrowwallet.frigate.http;

import com.sparrowwallet.frigate.index.Index;
import com.sparrowwallet.frigate.index.IndexMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

public class HttpApiServer {
    private static final Logger log = LoggerFactory.getLogger(HttpApiServer.class);
    public static final int DEFAULT_PORT = 8081;
    private static final int BATCH_SIZE = 20000;
    private static final int MAX_BATCH_SIZE = 50000;

    private final Index blocksIndex;
    private final int port;
    private HttpServer server;

    // Cached metadata
    private String tableName;
    private boolean hasCompressedColumn;

    public HttpApiServer(Index blocksIndex) {
        this(blocksIndex, DEFAULT_PORT);
    }

    public HttpApiServer(Index blocksIndex, int port) {
        this.blocksIndex = blocksIndex;
        this.port = port;
    }

    public void start() {
        try {
            detectTable();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/api/info", this::handleInfo);
            server.createContext("/api/batch", this::handleBatch);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            log.info("HTTP API server listening on port {}", port);
        } catch(Exception e) {
            log.error("Failed to start HTTP API server", e);
        }
    }

    public void stop() {
        if(server != null) {
            server.stop(0);
        }
    }

    private void detectTable() {
        try {
            if(blocksIndex.getIndexMode() == IndexMode.UTXO_ONLY) {
                tableName = "utxo";
                hasCompressedColumn = blocksIndex.executeRead(connection -> {
                    ResultSet rs = connection.createStatement().executeQuery(
                            "SELECT column_name FROM information_schema.columns WHERE table_name = 'utxo' AND column_name = 'compressed_tweak_key'");
                    return rs.next();
                });
            } else {
                tableName = "tweak";
                hasCompressedColumn = false;
            }
        } catch(Exception e) {
            log.error("Failed to detect table", e);
            tableName = "utxo";
            hasCompressedColumn = false;
        }
    }

    private void handleInfo(HttpExchange exchange) throws IOException {
        if(handleCors(exchange)) return;

        try {
            int totalRecords = blocksIndex.executeRead(connection -> {
                ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + tableName);
                rs.next();
                return rs.getInt(1);
            });

            String keyColumn = hasCompressedColumn ? "compressed_tweak_key" : "tweak_key";
            int keySize = blocksIndex.executeRead(connection -> {
                ResultSet rs = connection.createStatement().executeQuery("SELECT octet_length(" + keyColumn + ") FROM " + tableName + " LIMIT 1");
                return rs.next() ? rs.getInt(1) : 0;
            });

            String keyFormat = hasCompressedColumn ? "compressed" : (keySize == 33 ? "compressed" : "raw");

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"table\":\"").append(tableName).append("\",");
            json.append("\"total_records\":").append(totalRecords).append(",");
            json.append("\"batch_size\":").append(BATCH_SIZE).append(",");
            json.append("\"key_format\":\"").append(keyFormat).append("\",");
            json.append("\"key_size\":").append(keySize).append(",");
            json.append("\"encoding\":\"base64\",");
            json.append("\"mode\":\"duckdb\",");
            json.append("\"has_compressed_column\":").append(hasCompressedColumn);
            json.append("}");

            sendJson(exchange, json.toString());
        } catch(Exception e) {
            log.error("Error handling /api/info", e);
            sendJson(exchange, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleBatch(HttpExchange exchange) throws IOException {
        if(handleCors(exchange)) return;

        try {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());

            int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
            int limit = Math.min(Integer.parseInt(params.getOrDefault("limit", String.valueOf(BATCH_SIZE))), MAX_BATCH_SIZE);
            boolean scanMode = "1".equals(params.get("scan"));
            Integer startHeight = params.containsKey("start_height") ? Integer.parseInt(params.get("start_height")) : null;
            Integer endHeight = params.containsKey("end_height") ? Integer.parseInt(params.get("end_height")) : null;

            long t0 = System.nanoTime();

            String keyColumn = hasCompressedColumn ? "compressed_tweak_key" : "tweak_key";

            // Build WHERE clause
            List<String> conditions = new ArrayList<>();
            if(startHeight != null) conditions.add("height >= " + startHeight);
            if(endHeight != null) conditions.add("height <= " + endHeight);
            String whereClause = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

            // Get filtered total
            int filteredTotal;
            if(startHeight != null || endHeight != null) {
                filteredTotal = blocksIndex.executeRead(connection -> {
                    ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + tableName + whereClause);
                    rs.next();
                    return rs.getInt(1);
                });
            } else {
                filteredTotal = blocksIndex.executeRead(connection -> {
                    ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + tableName);
                    rs.next();
                    return rs.getInt(1);
                });
            }

            // Fetch records
            String records = blocksIndex.executeRead(connection -> {
                String sql;
                if("utxo".equals(tableName)) {
                    if(scanMode) {
                        sql = "SELECT " + keyColumn + ", output_hash_prefix, txid, output_index, height, value FROM " + tableName + whereClause + " LIMIT " + limit + " OFFSET " + offset;
                    } else {
                        sql = "SELECT txid, output_index, height, " + keyColumn + ", output_hash_prefix, value FROM " + tableName + whereClause + " LIMIT " + limit + " OFFSET " + offset;
                    }
                } else {
                    sql = "SELECT txid, height, tweak_key FROM " + tableName + whereClause + " LIMIT " + limit + " OFFSET " + offset;
                }

                ResultSet rs = connection.createStatement().executeQuery(sql);
                StringBuilder sb = new StringBuilder("[");
                int count = 0;

                while(rs.next()) {
                    if(count > 0) sb.append(",");

                    if("utxo".equals(tableName)) {
                        if(scanMode) {
                            byte[] key = rs.getBytes(1);
                            long hashPrefix = rs.getLong(2);
                            byte[] txid = rs.getBytes(3);
                            int outputIndex = rs.getInt(4);
                            int height = rs.getInt(5);
                            long value = rs.getLong(6);

                            sb.append("{\"k\":\"").append(Base64.getEncoder().encodeToString(key)).append("\",");
                            sb.append("\"p\":\"").append(hashPrefix).append("\",");
                            sb.append("\"t\":\"").append(Base64.getEncoder().encodeToString(txid)).append("\",");
                            sb.append("\"o\":").append(outputIndex).append(",");
                            sb.append("\"h\":").append(height).append(",");
                            sb.append("\"v\":").append(value).append("}");
                        } else {
                            byte[] txid = rs.getBytes(1);
                            int outputIndex = rs.getInt(2);
                            int height = rs.getInt(3);
                            byte[] key = rs.getBytes(4);
                            long hashPrefix = rs.getLong(5);
                            long value = rs.getLong(6);

                            sb.append("{\"txid\":\"").append(Base64.getEncoder().encodeToString(txid)).append("\",");
                            sb.append("\"output_index\":").append(outputIndex).append(",");
                            sb.append("\"height\":").append(height).append(",");
                            sb.append("\"tweak_key\":\"").append(Base64.getEncoder().encodeToString(key)).append("\",");
                            sb.append("\"output_hash_prefix\":\"").append(hashPrefix).append("\",");
                            sb.append("\"value\":").append(value).append("}");
                        }
                    } else {
                        byte[] txid = rs.getBytes(1);
                        int height = rs.getInt(2);
                        byte[] key = rs.getBytes(3);

                        sb.append("{\"txid\":\"").append(Base64.getEncoder().encodeToString(txid)).append("\",");
                        sb.append("\"height\":").append(height).append(",");
                        sb.append("\"tweak_key\":\"").append(Base64.getEncoder().encodeToString(key)).append("\"}");
                    }
                    count++;
                }

                sb.append("]");
                return count + "\t" + sb.toString();
            });

            double queryMs = (System.nanoTime() - t0) / 1_000_000.0;

            // Parse count from records string
            int tabIdx = records.indexOf('\t');
            int count = Integer.parseInt(records.substring(0, tabIdx));
            String recordsJson = records.substring(tabIdx + 1);

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"offset\":").append(offset).append(",");
            json.append("\"count\":").append(count).append(",");
            json.append("\"total\":").append(filteredTotal).append(",");
            json.append("\"has_more\":").append(offset + count < filteredTotal).append(",");
            json.append("\"query_ms\":").append(String.format("%.2f", queryMs)).append(",");
            json.append("\"records\":").append(recordsJson);
            if(startHeight != null || endHeight != null) {
                json.append(",\"filter\":{");
                json.append("\"start_height\":").append(startHeight != null ? startHeight : "null").append(",");
                json.append("\"end_height\":").append(endHeight != null ? endHeight : "null");
                json.append("}");
            }
            json.append("}");

            sendJson(exchange, json.toString());
        } catch(Exception e) {
            log.error("Error handling /api/batch", e);
            sendJson(exchange, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private boolean handleCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

        if("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] responseBytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");

        String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        if(acceptEncoding != null && acceptEncoding.contains("gzip") && responseBytes.length > 1000) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try(GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                gzos.write(responseBytes);
            }
            byte[] compressed = baos.toByteArray();

            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, compressed.length);
            try(OutputStream os = exchange.getResponseBody()) {
                os.write(compressed);
            }
        } else {
            exchange.sendResponseHeaders(200, responseBytes.length);
            try(OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if(query == null || query.isEmpty()) return params;

        for(String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if(eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private String escapeJson(String s) {
        if(s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
