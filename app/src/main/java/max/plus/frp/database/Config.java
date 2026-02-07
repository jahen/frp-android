package max.plus.frp.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.util.Objects;

@Entity

public class Config {

    @PrimaryKey
    @NonNull
    private String uid;
    private String name;
    private String cfg;
    private String format; // 配置格式: ini, toml, yaml, json
    private int sortOrder; // 排序位置，用于列表拖动排序
    @Ignore
    private Boolean connecting;
    @Ignore
    private Boolean starting; // 正在启动状态，用于防止启动期间的重复点击

    @Ignore
    public Config() {
    }

    @Ignore
    public Config(String cfg) {
        this.cfg = cfg;
    }

    public Config(@NonNull String uid, String name, String cfg) {
        this.uid = uid;
        this.name = name;
        this.cfg = cfg;
    }

    @NonNull
    public String getUid() {
        return uid;
    }

    public Config setUid(@NonNull String uid) {
        this.uid = uid;
        return this;
    }

    public String getName() {
        return name;
    }

    public Config setName(String name) {
        this.name = name;
        return this;
    }

    public Boolean getConnecting() {
        return connecting;
    }

    public Config setConnecting(Boolean connecting) {
        this.connecting = connecting;
        return this;
    }

    public Boolean getStarting() {
        return starting;
    }

    public Config setStarting(Boolean starting) {
        this.starting = starting;
        return this;
    }

    public String getCfg() {
        return cfg;
    }

    public Config setCfg(String cfg) {
        this.cfg = cfg;
        return this;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Config config = (Config) o;
        return Objects.equals(uid, config.uid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uid);
    }

    @Override
    public String toString() {
        return "Config{" +
                "uid='" + uid + '\'' +
                ", cfg='" + cfg + '\'' +
                '}';
    }
}
