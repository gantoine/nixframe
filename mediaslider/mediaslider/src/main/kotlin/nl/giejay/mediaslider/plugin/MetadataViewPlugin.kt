package nl.giejay.mediaslider.plugin

import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import androidx.constraintlayout.widget.ConstraintLayout
import com.zeuskartik.mediaslider.R
import android.os.Handler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.mediaslider.adapter.AlignOption
import nl.giejay.mediaslider.adapter.MetaDataAdapter
import nl.giejay.mediaslider.adapter.MetaDataClock
import nl.giejay.mediaslider.adapter.MetaDataMediaCount
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.view.MediaSliderController

class MetadataViewPlugin : SliderViewPlugin<MetadataRenderState>, SliderControllerPlugin {

    /** Pending fade-out of the overlay (see [MediaSliderConfiguration.metaDataAutoHideMillis]). */
    private var hideOverlay: Runnable? = null

    override fun createState(context: SliderViewPluginContext, config: MediaSliderConfiguration): MetadataRenderState {
        val rightAdapter = MetaDataAdapter(
            context.context,
            config.metaDataConfig.filter { it.align == AlignOption.RIGHT },
            config.metaDataConfig.map { it.withAlign(align = AlignOption.RIGHT) }.distinct(),
            {
                val currentItem = context.currentItemProvider()
                if (currentItem.hasSecondaryItem()) currentItem.secondaryItem!! else currentItem.mainItem
            },
            { context.currentItemProvider().hasSecondaryItem() }
        )

        val leftAdapter = MetaDataAdapter(
            context.context,
            config.metaDataConfig.filter { it.align == AlignOption.LEFT },
            config.metaDataConfig.filterNot { it is MetaDataClock || it is MetaDataMediaCount }
                .map { it.withAlign(align = AlignOption.LEFT) }
                .distinct(),
            { context.currentItemProvider().mainItem },
            { context.currentItemProvider().hasSecondaryItem() }
        )

        return MetadataRenderState(leftAdapter, rightAdapter)
    }

    override fun attachView(rootView: ConstraintLayout, state: MetadataRenderState?) {
        val view = View.inflate(rootView.context, R.layout.metadata_holder, null)
        val params = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        }
        rootView.addView(view, params)
    }

    override fun onLoadConfig(
        context: SliderViewPluginContext,
        config: MediaSliderConfiguration,
        state: MetadataRenderState?
    ) {
        val pluginState = state ?: return
        val listViewRight = context.rootView.findViewById<ListView>(R.id.metadata_view_right) ?: return
        listViewRight.divider = null
        listViewRight.adapter = pluginState.rightAdapter

        val listViewLeft = context.rootView.findViewById<ListView>(R.id.metadata_view_left) ?: return
        listViewLeft.divider = null
        listViewLeft.adapter = pluginState.leftAdapter
    }

    override fun onPageSettled(
        context: SliderViewPluginContext,
        config: MediaSliderConfiguration,
        sliderItem: SliderItemViewHolder,
        sliderItemIndex: Int,
        handler: Handler,
        state: MetadataRenderState?
    ) {
        val currentState = state ?: return
        val leftAdapter = currentState.leftAdapter
        val rightAdapter = currentState.rightAdapter

        // Disable the gradient overlay for video items if the config specifies it should be hidden
        val metaDataHolderView = context.rootView.findViewById<LinearLayout>(R.id.meta_data_holder)
        if (sliderItem.type == SliderItemType.VIDEO) {
            if (config.isGradiantOverlayVisible) {
                metaDataHolderView?.background = null
            }
        } else {
            if (config.isGradiantOverlayVisible) {
                metaDataHolderView?.setBackgroundResource(R.drawable.gradient_overlay)
            }
        }

        updateMetaData(context, leftAdapter, sliderItem.mainItem, sliderItemIndex, config)
        updateMetaData(
            context,
            rightAdapter,
            if (sliderItem.hasSecondaryItem()) sliderItem.secondaryItem!! else sliderItem.mainItem,
            sliderItemIndex,
            config
        )
        if (metaDataHolderView != null) showOverlayBriefly(metaDataHolderView, config.metaDataAutoHideMillis)
    }

    /**
     * Fades the overlay in for the new item, then (when [autoHideMillis] > 0) fades it out after
     * that long. A new item cancels any pending fade-out, so each item gets its full time.
     */
    private fun showOverlayBriefly(holder: View, autoHideMillis: Long) {
        if (autoHideMillis <= 0L) return
        hideOverlay?.let { holder.removeCallbacks(it) }
        holder.animate().cancel()
        holder.animate().alpha(1f).setDuration(OVERLAY_FADE_IN_MS).start()
        val hide = Runnable { holder.animate().alpha(0f).setDuration(OVERLAY_FADE_OUT_MS).start() }
        hideOverlay = hide
        holder.postDelayed(hide, autoHideMillis)
    }

    override fun onControllerVisibilityChanged(isVisible: Boolean, rootView: View, controller: MediaSliderController, config: MediaSliderConfiguration) {
        if(isVisible && config.isGradiantOverlayVisible) {
            rootView.findViewById<LinearLayout>(R.id.meta_data_holder)?.background = null
        } else if(!isVisible && config.isGradiantOverlayVisible) {
            rootView.findViewById<LinearLayout>(R.id.meta_data_holder)?.setBackgroundResource(R.drawable.gradient_overlay)
        }
    }

    override fun onPageSelected(context: SliderViewPluginContext, sliderItemIndex: Int, state: MetadataRenderState?) {
        val currentState = state ?: return
        currentState.leftAdapter.notifyDataSetChanged()
        currentState.rightAdapter.notifyDataSetChanged()
    }

    private fun updateMetaData(
        context: SliderViewPluginContext,
        adapter: MetaDataAdapter,
        sliderItem: SliderItem,
        sliderItemIndex: Int,
        config: MediaSliderConfiguration
    ) {
        context.ioScope.launch {
            adapter.getItemsToShow().forEachIndexed { metaDataIndex, item ->
                if (adapter.hasStateForItem(sliderItem.id, metaDataIndex)) {
                    return@forEachIndexed
                }
                val value = item.getValue(context.context, sliderItem, sliderItemIndex, config.items.size)
                adapter.updateState(sliderItem.id, metaDataIndex, value ?: "")
            }
            withContext(Dispatchers.Main) {
                adapter.notifyDataSetChanged()
            }
        }
    }

    private companion object {
        const val OVERLAY_FADE_IN_MS = 500L
        const val OVERLAY_FADE_OUT_MS = 900L
    }
}
