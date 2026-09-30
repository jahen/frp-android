package max.plus.frp.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.afollestad.materialdialogs.MaterialDialog;

import java.util.ArrayList;
import java.util.List;

import max.plus.frp.ImportedFileStore;
import max.plus.frp.R;
import max.plus.frp.adapter.ImportedFileAdapter;

/**
 * 管理用户导入到应用私有目录的文件（证书、密钥等），支持复制绝对路径供配置引用。
 */
public class ImportedFilesActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_IMPORT = 2001;

    private RecyclerView recyclerView;
    private TextView emptyView;
    private ImportedFileAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_imported_files);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
            getSupportActionBar().setTitle(R.string.title_imported_files);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);

        adapter = new ImportedFileAdapter();
        adapter.setOnItemChildClickListener((ad, view, position) -> {
            ImportedFileStore.ImportedFile item = adapter.getItem(position);
            if (view.getId() == R.id.btn_copy_path) {
                copyPath(item);
            } else if (view.getId() == R.id.btn_delete) {
                confirmDelete(item);
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        loadFiles();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_imported_files, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_import_file) {
            openImportPicker();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadFiles() {
        List<ImportedFileStore.ImportedFile> files = ImportedFileStore.listFiles(this);
        adapter.setList(files);
        emptyView.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(files.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.imported_files_pick)), REQUEST_CODE_IMPORT);
        } catch (android.content.ActivityNotFoundException ex) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("*/*");
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(Intent.createChooser(fallback, getString(R.string.imported_files_pick)), REQUEST_CODE_IMPORT);
            } catch (android.content.ActivityNotFoundException ex2) {
                Toast.makeText(this, R.string.imported_files_no_picker, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_IMPORT || resultCode != RESULT_OK || data == null) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData clipData = data.getClipData();
            for (int i = 0; i < clipData.getItemCount(); i++) {
                uris.add(clipData.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        importUris(uris);
    }

    private void importUris(final List<Uri> uris) {
        new Thread(() -> {
            int success = 0;
            String lastError = null;
            for (Uri uri : uris) {
                try {
                    ImportedFileStore.importFromUri(getApplicationContext(), uri);
                    success++;
                } catch (Exception e) {
                    lastError = e.getMessage();
                }
            }
            final int imported = success;
            final String error = lastError;
            runOnUiThread(() -> {
                loadFiles();
                if (imported > 0) {
                    Toast.makeText(this,
                            getString(R.string.imported_files_import_success, imported),
                            Toast.LENGTH_SHORT).show();
                }
                if (imported < uris.size()) {
                    String msg = getString(R.string.imported_files_import_failed);
                    if (error != null && !error.isEmpty()) {
                        msg += ": " + error;
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void copyPath(ImportedFileStore.ImportedFile item) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("path", item.absolutePath));
        Toast.makeText(this, R.string.copySuccess, Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(final ImportedFileStore.ImportedFile item) {
        new MaterialDialog.Builder(this)
                .title(R.string.imported_files_delete_title)
                .content(getString(R.string.imported_files_delete_confirm, item.name))
                .negativeText(R.string.cancel)
                .positiveText(R.string.done)
                .onPositive((dialog, which) -> {
                    if (ImportedFileStore.deleteFile(this, item.name)) {
                        loadFiles();
                        Toast.makeText(this, R.string.actionDeleteSuccess, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.actionDeleteFailed, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
