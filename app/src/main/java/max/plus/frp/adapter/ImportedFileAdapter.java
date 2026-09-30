package max.plus.frp.adapter;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.viewholder.BaseViewHolder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import max.plus.frp.ImportedFileStore;
import max.plus.frp.R;

public class ImportedFileAdapter extends BaseQuickAdapter<ImportedFileStore.ImportedFile, BaseViewHolder> {

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public ImportedFileAdapter() {
        super(R.layout.item_imported_file);
        addChildClickViewIds(R.id.btn_copy_path, R.id.btn_delete);
    }

    @Override
    protected void convert(@NonNull BaseViewHolder holder, ImportedFileStore.ImportedFile item) {
        holder.setText(R.id.tv_name, item.name);
        String meta = item.getFormattedSize();
        if (item.lastModified > 0) {
            meta += " · " + dateFormat.format(new Date(item.lastModified));
        }
        holder.setText(R.id.tv_meta, meta);
    }
}
