package max.plus.frp.ui;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import max.plus.frp.CommonUtils;
import max.plus.frp.R;
import com.github.ahmadaghazadeh.editor.widget.CodeEditor;

import butterknife.BindView;
import butterknife.ButterKnife;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class TemplateActivity extends AppCompatActivity {

    @BindView(R.id.editText)
    CodeEditor editText;
    @BindView(R.id.toolbar)
    Toolbar toolbar;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ini_edit);
        ButterKnife.bind(this);

        initToolbar();

        initEdit();


    }

    private void initEdit() {
        // 从Intent获取格式信息，如果没有则默认使用ini格式
        String format = getIntent().getStringExtra("format");
        if (format == null || format.isEmpty()) {
            format = "ini";
        }
        
        int rawId = 0;
        String serverType = MainActivity.serverType;
        
        // 根据格式和服务器类型选择对应的完整模板文件
        // INI 使用 _legacy_full（官方 legacy 文件），TOML 使用 _full_toml，YAML/JSON 使用完整模板
        if ("frps".equals(serverType)) {
            switch (format.toLowerCase()) {
                case "ini":
                    rawId = R.raw.frps_legacy_full;
                    break;
                case "toml":
                    rawId = R.raw.frps_full_toml;
                    break;
                case "yaml":
                    rawId = R.raw.frps_full_yaml;
                    break;
                case "json":
                    rawId = R.raw.frps_full_json;
                    break;
                default:
                    rawId = R.raw.frps_legacy_full;
                    break;
            }
        } else {
            switch (format.toLowerCase()) {
                case "ini":
                    rawId = R.raw.frpc_legacy_full;
                    break;
                case "toml":
                    rawId = R.raw.frpc_full_toml;
                    break;
                case "yaml":
                    rawId = R.raw.frpc_full_yaml;
                    break;
                case "json":
                    rawId = R.raw.frpc_full_json;
                    break;
                default:
                    rawId = R.raw.frpc_legacy_full;
                    break;
            }
        }
        
        CommonUtils.getStringFromRaw(TemplateActivity.this, rawId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<String>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {

                    }

                    @Override
                    public void onNext(@NonNull String content) {
                        editText.setText(content, 1);

                    }

                    @Override
                    public void onError(@NonNull Throwable e) {

                    }

                    @Override
                    public void onComplete() {

                    }
                });


    }

    private void initToolbar() {
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle(R.string.titleTemplate);
    }
}
