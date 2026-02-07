package max.plus.frp.adapter;

import android.content.res.ColorStateList;

import androidx.core.widget.ImageViewCompat;

import max.plus.frp.R;
import max.plus.frp.database.Config;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.viewholder.BaseViewHolder;

import org.jetbrains.annotations.NotNull;

import max.plus.frp.library.Client;

public class FileListAdapter extends BaseQuickAdapter<Config, BaseViewHolder> {


    public FileListAdapter() {
        super(R.layout.item_recycler_main);
    }



    @Override
    protected void convert(@NotNull BaseViewHolder baseViewHolder, Config file) {
        baseViewHolder.setText(R.id.tv_name, file.getName());
        boolean running =(file.getConnecting() != null && file.getConnecting())|| Client.isRunning(file.getUid());
        boolean starting = (file.getStarting() != null && file.getStarting());
        
        baseViewHolder.setImageResource(R.id.iv_play, running ? R.drawable.ic_stop_white : R.drawable.ic_play_white);
        ImageViewCompat.setImageTintList(baseViewHolder.getView(R.id.iv_play), ColorStateList.valueOf(getContext().getResources().getColor(running ? R.color.colorStop : R.color.black)));

        // 如果正在启动，禁用三个按钮（运行、修改、删除）
        if (starting) {
            baseViewHolder.getView(R.id.iv_play).setEnabled(false);
            baseViewHolder.getView(R.id.iv_play).setAlpha(0.5f);
            baseViewHolder.getView(R.id.iv_edit).setEnabled(false);
            baseViewHolder.getView(R.id.iv_edit).setAlpha(0.5f);
            baseViewHolder.getView(R.id.iv_delete).setEnabled(false);
            baseViewHolder.getView(R.id.iv_delete).setAlpha(0.5f);
        } else {
            baseViewHolder.getView(R.id.iv_play).setEnabled(true);
            baseViewHolder.getView(R.id.iv_play).setAlpha(1.0f);
            baseViewHolder.getView(R.id.iv_edit).setEnabled(true);
            baseViewHolder.getView(R.id.iv_edit).setAlpha(1.0f);
            baseViewHolder.getView(R.id.iv_delete).setEnabled(true);
            baseViewHolder.getView(R.id.iv_delete).setAlpha(1.0f);
        }

    }


}
