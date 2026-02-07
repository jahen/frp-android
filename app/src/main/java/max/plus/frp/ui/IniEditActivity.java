package max.plus.frp.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.afollestad.materialdialogs.MaterialDialog;
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

public class IniEditActivity extends AppCompatActivity {

    public static final String INTENT_EDIT_INI = "INTENT_EDIT_INI";
    @BindView(R.id.editText)
    CodeEditor editText;
    @BindView(R.id.toolbar)
    Toolbar toolbar;

    private Config config;

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
        new MaterialDialog.Builder(this)
                .title(TextUtils.isEmpty(config.getName()) ? R.string.titleInputFileName : R.string.titleModifyFileName)
                .canceledOnTouchOutside(false)
                .autoDismiss(false)
                .negativeText(R.string.cancel)
                .positiveText(R.string.done)
                .onNegative((dialog, which) -> dialog.dismiss())
                .input("", TextUtils.isEmpty(config.getName()) ? "" : config.getName(), false, (dialog, input) ->
                {
                    config.setName(input.toString())
                            .setCfg(editText.getText());
                    // 根据内容检测格式：有内容时从内容推断，内容为空时使用添加时选择的格式
                    String content = editText.getText();
                    String formatToSave;
                    if (content == null || content.trim().isEmpty()) {
                        formatToSave = (config.getFormat() != null && !config.getFormat().isEmpty())
                                ? config.getFormat() : "toml";
                    } else {
                        formatToSave = inferFormatFromContent(content);
                    }
                    config.setFormat(formatToSave);
                    Completable action;
                    if(MainActivity.serverType=="frps"){
                    action =
                            TextUtils.isEmpty(config.getUid()) ?
                            FrpsDatabase.getInstance(IniEditActivity.this)
                                    .configDao()
                                    .insert(config.setUid(UUID.randomUUID().toString())) :
                            FrpsDatabase.getInstance(IniEditActivity.this)
                                    .configDao()
                                    .update(config);
                    }else{
                    action =
                            TextUtils.isEmpty(config.getUid()) ?
                            FrpcDatabase.getInstance(IniEditActivity.this)
                                    .configDao()
                                    .insert(config.setUid(UUID.randomUUID().toString())) :
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
                }).show();
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
