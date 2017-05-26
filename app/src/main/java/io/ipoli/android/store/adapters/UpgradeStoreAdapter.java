package io.ipoli.android.store.adapters;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.support.transition.TransitionManager;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.CardView;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.squareup.otto.Bus;

import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import io.ipoli.android.R;
import io.ipoli.android.app.ui.formatters.DateFormatter;
import io.ipoli.android.store.events.BuyUpgradeEvent;
import io.ipoli.android.store.viewmodels.UpgradeViewModel;

/**
 * Created by Polina Zhelyazkova <polina@ipoli.io>
 * on 5/23/17.
 */

public class UpgradeStoreAdapter extends RecyclerView.Adapter<UpgradeStoreAdapter.ViewHolder> {
    private final Context context;
    private final Bus eventBus;
    private List<UpgradeViewModel> viewModels;

    private int[] colors = new int[]{
            R.color.md_green_300,
            R.color.md_indigo_300,
            R.color.md_blue_300,
            R.color.md_red_300,
            R.color.md_deep_orange_300,
            R.color.md_purple_300,
            R.color.md_orange_300,
            R.color.md_pink_300,
    };

    public UpgradeStoreAdapter(Context context, Bus eventBus, List<UpgradeViewModel> viewModels) {
        this.context = context;
        this.eventBus = eventBus;
        this.viewModels = viewModels;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.upgrade_store_item, parent, false));
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        UpgradeViewModel vm = viewModels.get(holder.getAdapterPosition());

        holder.title.setText(vm.getTitle());
        holder.shortDesc.setText(vm.getShortDescription());
        holder.longDesc.setText(vm.getLongDescription());
        holder.price.setText(vm.getPrice() + " life store_coins");
        holder.image.setImageResource(vm.getImage());
        holder.expand.setOnClickListener(v -> {
            if (holder.longDesc.getVisibility() == View.GONE) {
                TransitionManager.beginDelayedTransition(holder.container);
                holder.expand.animate().rotationBy(180).setDuration(500);
                holder.longDesc.setAlpha(1.0f);
                holder.longDesc.setVisibility(View.VISIBLE);
            } else {
                holder.expand.animate().rotationBy(-180).setDuration(500);
                holder.longDesc.animate().alpha(0.0f).setDuration(500).withEndAction(() ->
                        holder.longDesc.setVisibility(View.GONE));
            }
        });

        if (vm.isBought()) {
            holder.container.setBackgroundColor(ContextCompat.getColor(context, R.color.md_grey_50));
            holder.buy.setVisibility(View.INVISIBLE);
            holder.boughtDate.setVisibility(View.VISIBLE);
            holder.boughtDate.setText("Bought on " + DateFormatter.format(context, vm.getBoughtDate()));
        } else {
            holder.buy.setVisibility(View.VISIBLE);
            holder.boughtDate.setVisibility(View.GONE);
        }

        holder.buy.setOnClickListener(v -> eventBus.post(new BuyUpgradeEvent(vm.getUpgrade())));

        GradientDrawable drawable = (GradientDrawable) holder.imageContainer.getBackground();
        drawable.setColor(ContextCompat.getColor(context, colors[position % colors.length]));
    }

    @Override
    public int getItemCount() {
        return viewModels.size();
    }

    public void setViewModels(List<UpgradeViewModel> upgradeViewModels) {
        viewModels = upgradeViewModels;
        notifyDataSetChanged();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        @BindView(R.id.upgrade_container)
        CardView container;

        @BindView(R.id.upgrade_title)
        TextView title;

        @BindView(R.id.upgrade_short_desc)
        TextView shortDesc;

        @BindView(R.id.upgrade_long_desc)
        TextView longDesc;

        @BindView(R.id.upgrade_price)
        TextView price;

        @BindView(R.id.upgrade_bought_date)
        TextView boughtDate;

        @BindView(R.id.upgrade_image)
        ImageView image;

        @BindView(R.id.upgrade_image_background)
        ImageView imageContainer;

        @BindView(R.id.upgrade_buy)
        Button buy;

        @BindView(R.id.upgrade_expand)
        ImageButton expand;

        public ViewHolder(View itemView) {
            super(itemView);
            ButterKnife.bind(this, itemView);
        }
    }
}
