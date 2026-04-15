package com.sparrowwallet.frigate.io;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.sparrowwallet.frigate.index.IndexMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    public static final String TOML_CONFIG_FILENAME = "config.toml";
    public static final String JSON_CONFIG_FILENAME = "config";

    private CoreConfig core;
    private IndexConfig index;
    private ScanConfig scan;
    private ServerConfig server;
    private DatabaseConfig database;

    private static Config INSTANCE;

    private static final TomlMapper tomlMapper = TomlMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public Config() {
        core = new CoreConfig();
        index = new IndexConfig();
        scan = new ScanConfig();
        server = new ServerConfig();
        database = new DatabaseConfig();
    }

    private static File getTomlConfigFile() {
        return new File(Storage.getFrigateDir(), TOML_CONFIG_FILENAME);
    }

    private static File getJsonConfigFile() {
        return new File(Storage.getFrigateDir(), JSON_CONFIG_FILENAME);
    }

    private static Config load() {
        File tomlFile = getTomlConfigFile();
        File jsonFile = getJsonConfigFile();

        if(tomlFile.exists()) {
            try {
                Config config = tomlMapper.readValue(tomlFile, Config.class);
                if(config != null) {
                    return config;
                }
            } catch(Exception e) {
                log.error("Error reading " + tomlFile.getAbsolutePath(), e);
            }
        } else if(jsonFile.exists()) {
            try {
                Config config = migrateFromJson(jsonFile);
                if(config != null) {
                    saveToml(config, tomlFile);
                    File backupFile = new File(jsonFile.getPath() + ".bak");
                    jsonFile.renameTo(backupFile);
                    log.info("Migrated config from JSON to TOML (backup: {})", backupFile.getName());
                    return config;
                }
            } catch(Exception e) {
                log.error("Error migrating " + jsonFile.getAbsolutePath(), e);
            }
        } else {
            try {
                writeDefaultConfig(tomlFile);
            } catch(Exception e) {
                log.error("Error writing default config", e);
            }
        }

        return new Config();
    }

    private static Config migrateFromJson(File jsonFile) throws IOException {
        ObjectMapper jsonMapper = new ObjectMapper();
        jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonNode root = jsonMapper.readTree(jsonFile);

        Config config = new Config();

        if(root.has("coreServer")) {
            config.getCore().setServer(root.get("coreServer").asText());
        }
        if(root.has("coreAuthType")) {
            config.getCore().setAuthType(root.get("coreAuthType").asText());
        }
        if(root.has("coreDataDir")) {
            config.getCore().setDataDir(root.get("coreDataDir").asText());
        }
        if(root.has("coreAuth")) {
            config.getCore().setAuth(root.get("coreAuth").asText());
        }
        if(root.has("startIndexing")) {
            config.getCore().setConnect(root.get("startIndexing").asBoolean());
        }
        if(root.has("indexStartHeight")) {
            config.getIndex().setStartHeight(root.get("indexStartHeight").asInt());
        }
        if(root.has("scriptPubKeyCacheSize")) {
            int oldSize = root.get("scriptPubKeyCacheSize").asInt();
            config.getIndex().setCacheSize(formatCacheSize(oldSize));
        }
        if(root.has("indexMode")) {
            try {
                config.getIndex().setMode(IndexMode.valueOf(root.get("indexMode").asText()));
            } catch(IllegalArgumentException e) {
                log.warn("Unknown indexMode in legacy JSON config: " + root.get("indexMode").asText());
            }
        }
        if(root.has("utxoMinValue")) {
            config.getIndex().setUtxoMinValue(root.get("utxoMinValue").asLong());
        }
        if(root.has("lastIndexedBlockHeight")) {
            config.getIndex().lastIndexedBlockHeight = root.get("lastIndexedBlockHeight").asInt();
        }

        if(root.has("batchSize")) {
            config.getScan().setBatchSize(root.get("batchSize").asInt());
        }
        if(root.has("computeBackend")) {
            config.getScan().setComputeBackend(root.get("computeBackend").asText());
        }
        if(root.has("dbThreads")) {
            config.getScan().setDbThreads(root.get("dbThreads").asInt());
        }

        if(root.has("backendElectrumServer")) {
            config.getServer().setBackendElectrumServer(root.get("backendElectrumServer").asText());
        }

        if(root.has("dbUrl")) {
            config.getDatabase().setUrl(root.get("dbUrl").asText());
        }
        if(root.has("readDbUrls")) {
            List<String> urls = new java.util.ArrayList<>();
            root.get("readDbUrls").forEach(node -> urls.add(node.asText()));
            config.getDatabase().setReadUrls(urls);
        }

        return config;
    }

    private static String formatCacheSize(int size) {
        if(size >= 1_000_000 && size % 1_000_000 == 0) {
            return (size / 1_000_000) + "M";
        } else if(size >= 1_000 && size % 1_000 == 0) {
            return (size / 1_000) + "K";
        }
        return String.valueOf(size);
    }

    static int parseCacheSize(String value) {
        if(value == null || value.isEmpty()) {
            return 10_000_000;
        }
        String s = value.trim().toUpperCase();
        if(s.endsWith("M")) {
            return (int) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000_000);
        } else if(s.endsWith("K")) {
            return (int) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000);
        }
        return Integer.parseInt(s);
    }

    private static void writeDefaultConfig(File tomlFile) {
        try(InputStream is = Config.class.getResourceAsStream("/config.toml.default")) {
            if(is != null) {
                Storage.createOwnerOnlyFile(tomlFile);
                Files.copy(is, tomlFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch(IOException e) {
            log.error("Error writing default config", e);
        }
    }

    private static void saveToml(Config config, File tomlFile) {
        try {
            if(!tomlFile.exists()) {
                Storage.createOwnerOnlyFile(tomlFile);
            }
            tomlMapper.writeValue(tomlFile, config);
        } catch(IOException e) {
            log.error("Error writing config", e);
        }
    }

    public static synchronized Config get() {
        if(INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public synchronized void flush() {
        saveToml(this, getTomlConfigFile());
    }

    public CoreConfig getCore() {
        if(core == null) {
            core = new CoreConfig();
        }
        return core;
    }

    public IndexConfig getIndex() {
        if(index == null) {
            index = new IndexConfig();
        }
        return index;
    }

    public ScanConfig getScan() {
        if(scan == null) {
            scan = new ScanConfig();
        }
        return scan;
    }

    public ServerConfig getServer() {
        if(server == null) {
            server = new ServerConfig();
        }
        return server;
    }

    public DatabaseConfig getDatabase() {
        if(database == null) {
            database = new DatabaseConfig();
        }
        return database;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CoreConfig {
        private Boolean connect;
        private String server;
        private String authType;
        private String dataDir;
        private String auth;

        public Boolean getConnect() {
            return connect;
        }

        public void setConnect(Boolean connect) {
            this.connect = connect;
        }

        @JsonIgnore
        public boolean shouldConnect() {
            return connect == null || connect;
        }

        public String getServer() {
            return server;
        }

        public void setServer(String server) {
            this.server = server;
        }

        public String getAuthType() {
            return authType;
        }

        public void setAuthType(String authType) {
            this.authType = authType;
        }

        @JsonIgnore
        public CoreAuthType getAuthTypeEnum() {
            if(authType == null) {
                return null;
            }
            try {
                return CoreAuthType.valueOf(authType);
            } catch(Exception e) {
                return null;
            }
        }

        public String getDataDir() {
            return dataDir;
        }

        public void setDataDir(String dataDir) {
            this.dataDir = dataDir;
        }

        @JsonIgnore
        public File getDataDirFile() {
            return dataDir != null ? new File(dataDir) : null;
        }

        public String getAuth() {
            return auth;
        }

        public void setAuth(String auth) {
            this.auth = auth;
        }

        @JsonIgnore
        public Server getServerObj() {
            return server != null ? Server.fromString(server) : null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IndexConfig {
        private Integer startHeight;
        private String cacheSize;
        private IndexMode mode;
        private Long utxoMinValue;
        private Integer lastIndexedBlockHeight;

        public Integer getStartHeight() {
            return startHeight;
        }

        public void setStartHeight(Integer startHeight) {
            this.startHeight = startHeight;
        }

        public String getCacheSize() {
            return cacheSize;
        }

        public void setCacheSize(String cacheSize) {
            this.cacheSize = cacheSize;
        }

        @JsonIgnore
        public int getCacheSizeEntries() {
            return Config.parseCacheSize(cacheSize);
        }

        public IndexMode getMode() {
            return mode == null ? IndexMode.FULL : mode;
        }

        public void setMode(IndexMode mode) {
            this.mode = mode;
        }

        public long getUtxoMinValue() {
            return utxoMinValue == null ? 1000L : utxoMinValue;
        }

        public void setUtxoMinValue(Long utxoMinValue) {
            this.utxoMinValue = utxoMinValue;
        }

        public Integer getLastIndexedBlockHeight() {
            return lastIndexedBlockHeight;
        }

        public void setLastIndexedBlockHeight(Integer lastIndexedBlockHeight) {
            this.lastIndexedBlockHeight = lastIndexedBlockHeight;
            // Skip flush during initial deserialization — Config.INSTANCE is null
            // while Jackson is still building the Config object, and calling
            // Config.get() here would re-enter load() and stack-overflow.
            if(Config.INSTANCE != null) {
                Config.INSTANCE.flush();
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScanConfig {
        public static final int DEFAULT_BATCH_SIZE = 300_000;

        private Integer batchSize;
        private String computeBackend;
        private Integer dbThreads;

        public int getBatchSize() {
            return batchSize != null ? batchSize : DEFAULT_BATCH_SIZE;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        @JsonIgnore
        public ComputeBackend getComputeBackendEnum() {
            if(computeBackend == null) {
                return ComputeBackend.AUTO;
            }
            try {
                return ComputeBackend.valueOf(computeBackend);
            } catch(Exception e) {
                return ComputeBackend.AUTO;
            }
        }

        public String getComputeBackend() {
            return computeBackend;
        }

        public void setComputeBackend(String computeBackend) {
            this.computeBackend = computeBackend;
        }

        public Integer getDbThreads() {
            return dbThreads;
        }

        public void setDbThreads(Integer dbThreads) {
            this.dbThreads = dbThreads;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ServerConfig {
        private Integer port;
        private String backendElectrumServer;

        public int getPort() {
            return port != null ? port : com.sparrowwallet.frigate.electrum.ElectrumServerRunnable.DEFAULT_PORT;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getBackendElectrumServer() {
            return backendElectrumServer;
        }

        public void setBackendElectrumServer(String backendElectrumServer) {
            this.backendElectrumServer = backendElectrumServer;
        }

        @JsonIgnore
        public Server getBackendElectrumServerObj() {
            return backendElectrumServer != null ? Server.fromString(backendElectrumServer) : null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DatabaseConfig {
        private String url;
        private List<String> readUrls;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public List<String> getReadUrls() {
            return readUrls;
        }

        public void setReadUrls(List<String> readUrls) {
            this.readUrls = readUrls;
        }
    }
}
