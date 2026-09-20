package com.tans.tasciiartplayer.ui.main

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.tans.tasciiartplayer.R
import com.tans.tasciiartplayer.databinding.ImageItemLayoutBinding
import com.tans.tasciiartplayer.databinding.ImagesFragmentBinding
import com.tans.tasciiartplayer.image.ImageManager
import com.tans.tasciiartplayer.image.ImageModel
import com.tans.tasciiartplayer.ui.imageascii.ImageAsciiActivity
import com.tans.tuiutils.adapter.impl.builders.SimpleAdapterBuilderImpl
import com.tans.tuiutils.adapter.impl.databinders.DataBinderImpl
import com.tans.tuiutils.adapter.impl.datasources.FlowDataSourceImpl
import com.tans.tuiutils.adapter.impl.viewcreatators.SingleItemViewCreatorImpl
import com.tans.tuiutils.dialog.dp2px
import com.tans.tuiutils.fragment.BaseCoroutineStateFragment
import com.tans.tuiutils.view.clicks
import com.tans.tuiutils.view.refreshes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The pictures of the device, click one to convert it to ascii art.
 */
class ImagesFragment : BaseCoroutineStateFragment<ImagesFragment.Companion.State>(State()) {

    override val layoutId: Int = R.layout.images_fragment

    override fun CoroutineScope.firstLaunchInitDataCoroutine() {

        launch {
            ImageManager.refreshMediaStoreImages()
        }

        launch {
            ImageManager.stateFlow
                .map { it.images.sortedByDescending { image -> image.mediaStoreImage.dateModified } }
                .distinctUntilChanged()
                .collect { images -> updateState { it.copy(images = images) } }
        }
    }

    @OptIn(FlowPreview::class)
    override fun CoroutineScope.bindContentViewCoroutine(contentView: View) {
        val viewBinding = ImagesFragmentBinding.bind(contentView)
        viewBinding.refreshLayout.refreshes(this, Dispatchers.IO) {
            ImageManager.refreshMediaStoreImages()
        }
        val glideLoadManager = Glide.with(this@ImagesFragment)
        val imageAdapterBuilder = SimpleAdapterBuilderImpl(
            itemViewCreator = SingleItemViewCreatorImpl(R.layout.image_item_layout),
            dataSource = FlowDataSourceImpl(
                dataFlow = stateFlow().map { it.images },
                getDataItemIdParam = { d, _ -> d.id },
                areDataItemsTheSameParam = { d1, d2 -> d1.id == d2.id },
                getDataItemsChangePayloadParam = { d1, d2 ->
                    if (d1.mediaStoreImage == d2.mediaStoreImage) Unit else null
                }
            ),
            dataBinder = DataBinderImpl<ImageModel> { image, view, _ ->
                val itemViewBinding = ImageItemLayoutBinding.bind(view)
                itemViewBinding.imageTitleTv.text = image.displayName
            }.addPayloadDataBinder(Unit) { image, view, _ ->
                val itemViewBinding = ImageItemLayoutBinding.bind(view)
                glideLoadManager
                    .load(image.mediaStoreImage.uri)
                    .error(R.drawable.icon_image)
                    .into(itemViewBinding.imageIv)
                itemViewBinding.root.clicks(this) {
                    startActivity(
                        ImageAsciiActivity.createIntent(
                            context = requireActivity(),
                            uri = image.mediaStoreImage.uri,
                            displayName = image.displayName
                        )
                    )
                }
            }
        )

        viewBinding.imagesRv.adapter = imageAdapterBuilder.build()

        launch {
            stateFlow()
                .map { it.images.isEmpty() }
                .debounce(200L)
                .distinctUntilChanged()
                .flowOn(Dispatchers.IO)
                .collect {
                    if (it) {
                        viewBinding.emptyTv.visibility = View.VISIBLE
                        viewBinding.imagesRv.visibility = View.INVISIBLE
                    } else {
                        viewBinding.emptyTv.visibility = View.INVISIBLE
                        viewBinding.imagesRv.visibility = View.VISIBLE
                    }
                }
        }

        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.imagesRv) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, systemBars.bottom + requireContext().dp2px(8))
            insets
        }
    }

    companion object {

        data class State(
            val images: List<ImageModel> = emptyList()
        )
    }
}
