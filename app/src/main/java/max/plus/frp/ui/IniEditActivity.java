package max.plus.frp.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.afollestad.materialdialogs.MaterialDialog;
import max.plus.frp.ConfigFileStore;
import max.plus.frp.R;
import max.plus.frp.database.FrpcDatabase;
import max.plus.frp.database.FrpsDatabase;
import max.plus.frp.database.Config;
import com.github.ahmadaghazadeh.editor.widget.CodeEditor;
import com.jeremyliao.liveeventbus.LiveEventBus;

import java.util.UUID;

import butterknife.BindView;
import butterknife.ButterKnife;
import io.reactivex.Completable;
import io.reactivex.CompletableObserver;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import java.io.File;

public class IniEditActivity extends AppCompatActivity {

    public static final String INTENT_EDIT_INI = "INTENT_EDIT_INI";
    @BindView(R.id.editText)
    CodeEditor editText;
    @BindView(R.id.toolbar)
    Toolbar toolbar;

    private Config config;
    private static final String[] SUPPORTED_FORMATS = {"ini", "toml", "yaml", "json"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ini_edit);
        ButterKnife.bind(this);
        initToolbar();

        LiveEventBus.get(INTENT_EDIT_INI, Config.class).observeSticky(this, value -> {
            config = value;
            editText.setText(config.getCfg(), 1);
            toolbar.setTitle(TextUtils.isEmpty(config.getName()) ? getString(R.string.noName) : config.getName());
        });


    }


    private void initToolbar() {
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_template:
                Intent templateIntent = new Intent(IniEditActivity.this, TemplateActivity.class);
                // 传递当前配置的格式信息
                if (config != null && config.getFormat() != null) {
                    templateIntent.putExtra("format", config.getFormat());
                } else {
                    // 如果没有格式信息，尝试从配置内容推断
                    String inferredFormat = inferFormatFromContent(config != null ? config.getCfg() : "");
                    templateIntent.putExtra("format", inferredFormat);
                }
                startActivity(templateIntent);
                break;
            case R.id.action_save:
                actionSave();
                break;
        }
        return super.onOptionsItemSelected(item);
    }


    private void actionSave() {
        MaterialDialog saveDialog = new MaterialDialog.Builder(this)
                .title(TextUtils.isEmpty(config.getName()) ? R.string.titleInputFileName : R.string.titleModifyFileName)
                .canceledOnTouchOutside(false)
                .autoDismiss(false)
                .negativeText(R.string.cancel)
                .positiveText(R.string.done)
                .onNegative((dialog, which) -> dialog.dismiss())
                .customView(R.layout.dialog_save_config, false)
                .onPositive((dialog, which) -> {
                    View customView = dialog.getCustomView();
                    if (customView == null) {
                        return;
                    }
                    final String oldName = config.getName();
                    final String oldFormat = config.getFormat();
                    final boolean isNewConfig = TextUtils.isEmpty(config.getUid());
                    EditText etName = customView.findViewById(R.id.et_config_name);
                    Spinner spFormat = customView.findViewById(R.id.sp_config_format);
                    String rawName = etName.getText() != null ? etName.getText().toString().trim() : "";
                    String selectedFormat = getSelectedFormat(spFormat);
                    String normalizedName = normalizeConfigName(rawName);
                    String type = MainActivity.serverType == "frps" ? "frps" : "frpc";
                    boolean isNew = TextUtils.isEmpty(config.getUid());
                    if (isNew) {
                        config.setUid(UUID.randomUUID().toString());
                    }

                    String rawCfg = editText.getText();
                    File targetFile = ConfigFileStore.getConfigFile(
                            IniEditActivity.this.getApplicationContext(),
                            type,
                            config.getUid(),
                            normalizedName,
                            selectedFormat
                    );
                    String normalizedCfg = ConfigFileStore.normalizeStorePath(rawCfg, targetFile.getParentFile());

                    config.setName(normalizedName)
                            .setCfg(normalizedCfg);
                    config.setFormat(selectedFormat);
                    Completable action;
                    if(MainActivity.serverType=="frps"){
                    action =
                            isNew ?
                            FrpsDatabase.getInstance(IniEditActivity.this)
                                    .configDao()
                                    .insert(config) :
                            FrpsDatabase.getInstance(IniEditActivity.this)
                                    .configDao()
                                    .update(config);
                    }else{
                    action =
                            isNew ?
                            FrpcDatabase.getInstance(IniEditActivity.this)
                                    .configDao()
                                    .insert(config) :
                            FrpcDatabase.getInstance(IniEditActivity.this)
                                    .configDao()
                                    .update(config);
                    }
                    action
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new CompletableObserver() {
                                @Override
                                public void onSubscribe(@NonNull Disposable d) {

                                }

                                @Override
                                public void onComplete() {
                                    try {
                                        java.io.File newFile = ConfigFileStore.writeConfigAtomic(
                                                IniEditActivity.this.getApplicationContext(),
                                                type,
                                                config.getUid(),
                                                config.getName(),
                                                config.getFormat(),
                                                config.getCfg()
                                        );
                                        // 改名/改格式后，清理旧文件，保证每条配置仅保留一份文件
                                        if (!isNewConfig) {
                                            java.io.File oldFile = ConfigFileStore.getConfigFile(
                                                    IniEditActivity.this.getApplicationContext(),
                                                    type,
                                                    config.getUid(),
                                                    oldName,
                                                    oldFormat
                                            );
                                            if (!oldFile.equals(newFile) && oldFile.exists()) {
                                                oldFile.delete();
                                            }
                                        }
                                    } catch (Exception e) {
                                        Toast.makeText(IniEditActivity.this, "配置已保存到数据库，但写入文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                    }
                                    Toast.makeText(IniEditActivity.this.getApplicationContext(), R.string.tipSaveSuccess, Toast.LENGTH_SHORT).show();
                                    dialog.dismiss();
                                    if(MainActivity.serverType=="frps"){
                                        LiveEventBus.get(HomeFragmentFrps.EVENT_UPDATE_CONFIG).post(config);
                                    }else{
                                        LiveEventBus.get(HomeFragment.EVENT_UPDATE_CONFIG).post(config);
                                    }
                                    finish();
                                }

                                @Override
                                public void onError(@NonNull Throwable e) {
                                    Toast.makeText(IniEditActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();

                                }
                            });
                })
                .show();
        initSaveDialogViews(saveDialog);
    }

    private void initSaveDialogViews(MaterialDialog dialog) {
        View customView = dialog.getCustomView();
        if (customView == null) {
            return;
        }
        EditText etName = customView.findViewById(R.id.et_config_name);
        Spinner spFormat = customView.findViewById(R.id.sp_config_format);

        etName.setText(TextUtils.isEmpty(config.getName()) ? "" : config.getName());

        String[] labels = new String[]{
                getString(R.string.config_format_ini),
                getString(R.string.config_format_toml),
                getString(R.string.config_format_yaml),
                getString(R.string.config_format_json)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFormat.setAdapter(adapter);

        int index = getFormatIndex(config.getFormat());
        spFormat.setSelection(index);
    }

    private int getFormatIndex(String format) {
        String f = normalizeFormat(format);
        for (int i = 0; i < SUPPORTED_FORMATS.length; i++) {
            if (SUPPORTED_FORMATS[i].equals(f)) {
                return i;
            }
        }
        return 1;
    }

    private String getSelectedFormat(Spinner spinner) {
        int idx = spinner.getSelectedItemPosition();
        if (idx < 0 || idx >= SUPPORTED_FORMATS.length) {
            return "toml";
        }
        return SUPPORTED_FORMATS[idx];
    }

    private String normalizeFormat(String format) {
        if (format == null) {
            return "toml";
        }
        String f = format.trim().toLowerCase();
        if ("yml".equals(f)) {
            return "yaml";
        }
        if ("ini".equals(f) || "toml".equals(f) || "yaml".equals(f) || "json".equals(f)) {
            return f;
        }
        return "toml";
    }

    private String normalizeConfigName(String name) {
        return TextUtils.isEmpty(name) ? getString(R.string.noName) : name.trim();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_add_text, menu);
        return true;
    }

    /**
     * 从配置内容推断格式类型
     */
    private String inferFormatFromContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "toml";
        }
        
        String trimmed = content.trim();
        
        // JSON格式：以{或[开头
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return "json";
        }
        
        // YAML格式：通常包含冒号和缩进，且以key: value形式
        if (trimmed.contains(":") && !trimmed.contains("=") && !trimmed.startsWith("[")) {
            // 检查是否是YAML格式（包含缩进和冒号）
            String[] lines = trimmed.split("\n");
            boolean hasYamlIndent = false;
            for (String line : lines) {
                if (line.matches("^\\s+[a-zA-Z_][a-zA-Z0-9_]*:.*")) {
                    hasYamlIndent = true;
                    break;
                }
            }
            if (hasYamlIndent) {
                return "yaml";
            }
        }
        
        // TOML格式：包含[section]但格式不同于INI，或者包含[[数组]]
        if (trimmed.contains("[[") || (trimmed.contains("[") && trimmed.contains("=") && !trimmed.contains("[common]"))) {
            // 检查是否是TOML（通常有[section]但没有[common]这样的INI风格）
            if (trimmed.contains("=") && (trimmed.contains("\"") || trimmed.contains("[[") || trimmed.contains("serverAddr"))) {
                return "toml";
            }
        }
        
        // 无法确定时默认 toml
        return "toml";
    }
}
