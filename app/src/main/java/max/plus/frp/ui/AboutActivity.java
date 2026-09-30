package max.plus.frp.ui;

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import max.plus.frp.R;

/**
 * 致谢页面，展示 App 作者、贡献者和 frp 项目信息
 */
public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
            getSupportActionBar().setTitle(R.string.app_repository);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // 使链接可点击
        TextView tvAppRepo = findViewById(R.id.tv_app_repo);
        tvAppRepo.setMovementMethod(LinkMovementMethod.getInstance());

        TextView tvFrpRepo = findViewById(R.id.tv_frp_repo);
        tvFrpRepo.setMovementMethod(LinkMovementMethod.getInstance());
    }
}
