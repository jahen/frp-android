package max.plus.frp.adapter;

import android.content.res.ColorStateList;

import androidx.core.widget.ImageViewCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.viewholder.BaseViewHolder;
import max.plus.frp.R;
import max.plus.frp.database.Config;

import org.jetbrains.annotations.NotNull;

import max.plus.frp.library.Server;

public class FileListAdapterFrps extends BaseQuickAdapter<Config, BaseViewHolder> {
    public BaseViewHolder viewHolder;


    public FileListAdapterFrps() {
        super(R.layout.item_recycler_main);
    }



    @Override
    protected void convert(@NotNull BaseViewHolder baseViewHolder, Config file) {
        baseViewHolder.setText(R.id.tv_name, file.getName());
        // 注意：frps 运行在独立进程，UI 进程中无法可靠调用 Server.isRunning 获取状态
        // 这里仅根据本地的 connecting 状态来控制图标显示，真正的运行状态由 HomeFragmentFrps 通过事件同步
        boolean running = (file.getConnecting() != null && file.getConnecting());
        boolean starting = (file.getStarting() != null && file.getStarting());
        
        baseViewHolder.setImageResource(R.id.iv_play, running ? R.drawable.ic_stop_white : R.drawable.ic_play_white);
        ImageViewCompat.setImageTintList(baseViewHolder.getView(R.id.iv_play), ColorStateList.valueOf(getContext().getResources().getColor(running ? R.color.colorStop : R.color.black)));

        // 显示播放按钮，允许每条配置单独启动
        baseViewHolder.setGone(R.id.iv_play, false);

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

//        baseViewHolder.setImageResource(R.id.iv_play, R.drawable.ic_delete_white);
//        ImageViewCompat.setImageTintList(baseViewHolder.getView(R.id.iv_play), ColorStateList.valueOf(getContext().getResources().getColor(R.color.colorStop)));

    }


}
