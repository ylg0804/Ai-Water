package com.model.water.ezviz;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

/** Offline inputs are server configuration only, never client-supplied paths/URLs. */
@Component
@ConfigurationProperties(prefix = "waterflow.test-source")
public class TestSourceProperties {
    private boolean enabled;
    private String root = "test-videos";
    private Map<String, String> files = new HashMap<>(), urls = new HashMap<>();
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRoot() { return root; }
    public void setRoot(String root) { this.root = root; }
    public Map<String, String> getFiles() { return files; }
    public void setFiles(Map<String, String> files) { this.files = files; }
    public Map<String, String> getUrls() { return urls; }
    public void setUrls(Map<String, String> urls) { this.urls = urls; }
}
