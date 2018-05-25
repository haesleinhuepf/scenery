package graphics.scenery.backends.vulkan

import ab.appBuffer
import glm_.set
import graphics.scenery.utils.LazyLogger
import org.lwjgl.vulkan.*
import java.util.*
import org.lwjgl.vulkan.VK10.*
import java.nio.LongBuffer
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.system.Struct
import vkk.*

/**
 *

 * @author Ulrik Günther @ulrik.is>
 */
open class VulkanFramebuffer(
    protected val device: VulkanDevice,
    protected var commandPool: VkCommandPool,
    var width: Int, // TODO Vec2i glm?
    var height: Int,
    val commandBuffer: VkCommandBuffer,
    var shouldClear: Boolean = true, val sRGB: Boolean = false) : AutoCloseable {

    protected val logger by LazyLogger()

    var framebuffer: VkFramebuffer = NULL
    var renderPass: VkRenderPass = NULL
    var framebufferSampler: VkSampler = NULL
    var outputDescriptorSet: VkDescriptorSet = NULL

    protected var initialized: Boolean = false

    enum class AttachmentType { COLOR, DEPTH }

    inner class VulkanFramebufferAttachment : AutoCloseable {
        var image: VkImage = NULL
        var memory: VkDeviceMemory = NULL
        var imageView: VkImageView = NULL
        var format = VkFormat.UNDEFINED

        var type: AttachmentType = AttachmentType.COLOR
        var desc: VkAttachmentDescription = VkAttachmentDescription.calloc()

        var fromSwapchain = false

        override fun close() {

            device.vulkanDevice.apply {

                destroyImageView(imageView)

                if (image != NULL && !fromSwapchain) {
                    destroyImage(image)
                }

                if (memory != NULL) {
                    freeMemory(memory)
                }
            }
            desc.free()
        }
    }

    var attachments = LinkedHashMap<String, VulkanFramebufferAttachment>()

    protected fun createAttachment(format: VkFormat, usage: VkImageUsage, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebufferAttachment {

        val a = VulkanFramebufferAttachment()
        var aspectMask: VkImageAspectFlags = 0
        var imageLayout = VkImageLayout.UNDEFINED

        a.format = format

        if (usage == VkImageUsage.COLOR_ATTACHMENT_BIT) {
            aspectMask = VkImageAspect.COLOR_BIT.i
            imageLayout = VkImageLayout.SHADER_READ_ONLY_OPTIMAL
        }

        if (usage == VkImageUsage.DEPTH_STENCIL_ATTACHMENT_BIT) {
            aspectMask = VkImageAspect.DEPTH_BIT.i
            imageLayout = VkImageLayout.SHADER_READ_ONLY_OPTIMAL
        }

        val imageExtent = vk.Extent3D {
            width = attachmentWidth
            height = attachmentHeight
            depth = 1
        }
        val image = vk.ImageCreateInfo {
            imageType = VkImageType.`2D`
            this.format = a.format
            extent = imageExtent
            mipLevels = 1
            arrayLayers = 1
            samples = VkSampleCount.`1_BIT`
            tiling = VkImageTiling.OPTIMAL
            this.usage = usage or VkImageUsage.SAMPLED_BIT or VkImageUsage.TRANSFER_SRC_BIT or VkImageUsage.TRANSFER_DST_BIT
        }

        a.image = device.vulkanDevice.createImage(image)

        val requirements = device.vulkanDevice.getImageMemoryRequirements(a.image)

        val allocation = VkMemoryAllocateInfo.calloc()
            .allocationSize(requirements.size())
            .memoryTypeIndex(device.getMemoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT).first())
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .pNext(NULL)

        a.memory = device.vulkanDevice.allocateMemory(allocation)
        device.vulkanDevice.bindImageMemory(a.image, a.memory)

        VU.setImageLayout(
            commandBuffer,
            a.image,
            aspectMask,
            VkImageLayout.UNDEFINED,
            when (usage) {
                VkImageUsage.COLOR_ATTACHMENT_BIT -> VkImageLayout.SHADER_READ_ONLY_OPTIMAL
                else -> imageLayout
            }
        )

        val subresourceRange = vk.ImageSubresourceRange {
            this.aspectMask = aspectMask
            baseMipLevel = 0
            levelCount = 1
            baseArrayLayer = 0
            layerCount = 1
        }
        val iv = vk.ImageViewCreateInfo {
            viewType = VkImageViewType.`2D`
            this.format = format
            this.subresourceRange = subresourceRange
            this.image = a.image
        }

        a.imageView = device.vulkanDevice.createImageView(iv)

        return a
    }

    private fun createAndAddDepthStencilAttachmentInternal(name: String, format: VkFormat, attachmentWidth: Int, attachmentHeight: Int) {

        val att = createAttachment(format, VkImageUsage.DEPTH_STENCIL_ATTACHMENT_BIT, attachmentWidth, attachmentHeight)

        val (loadOp, stencilLoadOp) = when {
            !shouldClear -> VkAttachmentLoadOp.DONT_CARE to VkAttachmentLoadOp.LOAD
            else -> VkAttachmentLoadOp.CLEAR to VkAttachmentLoadOp.CLEAR
        }

        val initialImageLayout = VkImageLayout.UNDEFINED

        att.desc.apply {
            samples = VkSampleCount.`1_BIT`
            this.loadOp = loadOp
            storeOp = VkAttachmentStoreOp.STORE
            this.stencilLoadOp = stencilLoadOp
            stencilStoreOp = VkAttachmentStoreOp.STORE
            initialLayout = initialImageLayout
            finalLayout = VkImageLayout.SHADER_READ_ONLY_OPTIMAL
            this.format = format
        }
        att.type = AttachmentType.DEPTH

        attachments[name] = att
    }

    private fun createAndAddColorAttachmentInternal(name: String, format: VkFormat, attachmentWidth: Int, attachmentHeight: Int) {

        val att = createAttachment(format, VkImageUsage.COLOR_ATTACHMENT_BIT, attachmentWidth, attachmentHeight)

        val (loadOp, stencilLoadOp) = when {
            !shouldClear -> VkAttachmentLoadOp.LOAD to VkAttachmentLoadOp.LOAD
            else -> VkAttachmentLoadOp.CLEAR to VkAttachmentLoadOp.DONT_CARE
        }

        val initialImageLayout = when {
            !shouldClear -> VkImageLayout.COLOR_ATTACHMENT_OPTIMAL
            else -> VkImageLayout.UNDEFINED
        }

        att.desc.apply {
            samples = VkSampleCount.`1_BIT`
            this.loadOp = loadOp
            storeOp = VkAttachmentStoreOp.STORE
            this.stencilLoadOp = stencilLoadOp
            stencilStoreOp = VkAttachmentStoreOp.DONT_CARE
            initialLayout = initialImageLayout
            finalLayout = VkImageLayout.SHADER_READ_ONLY_OPTIMAL
            this.format = format
        }
        attachments[name] = att
    }

    fun addFloatBuffer(name: String, channelDepth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {

        val format = when (channelDepth) {
            16 -> VkFormat.R16_SFLOAT
            32 -> VkFormat.R32_SFLOAT
            else -> {
                logger.warn("Unsupported channel depth $channelDepth, using 16 bit.")
                VkFormat.R16_SFLOAT
            }
        }

        createAndAddColorAttachmentInternal(name, format, attachmentWidth, attachmentHeight)

        return this
    }

    fun addFloatRGBuffer(name: String, channelDepth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {

        val format = when (channelDepth) {
            16 -> VkFormat.R16G16_SFLOAT
            32 -> VkFormat.R32G32_SFLOAT
            else -> {
                logger.warn("Unsupported channel depth $channelDepth, using 16 bit.")
                VkFormat.R16G16_SFLOAT
            }
        }

        createAndAddColorAttachmentInternal(name, format, attachmentWidth, attachmentHeight)

        return this
    }

    fun addFloatRGBBuffer(name: String, channelDepth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {

        val format = when (channelDepth) {
            16 -> VkFormat.R16G16B16_SFLOAT
            32 -> VkFormat.R32G32B32_SFLOAT
            else -> {
                logger.warn("Unsupported channel depth $channelDepth, using 16 bit.")
                VkFormat.R16G16B16A16_SFLOAT
            }
        }

        createAndAddColorAttachmentInternal(name, format, attachmentWidth, attachmentHeight)

        return this
    }

    fun addFloatRGBABuffer(name: String, channelDepth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {

        val format = when (channelDepth) {
            16 -> VkFormat.R16G16B16A16_SFLOAT
            32 -> VkFormat.R32G32B32A32_SFLOAT
            else -> {
                logger.warn("Unsupported channel depth $channelDepth, using 16 bit.")
                VkFormat.R16G16B16A16_SFLOAT
            }
        }

        createAndAddColorAttachmentInternal(name, format, attachmentWidth, attachmentHeight)

        return this
    }

    fun addUnsignedByteRGBABuffer(name: String, channelDepth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {

        val format = when (channelDepth) {
            8 -> when {
                sRGB -> VkFormat.R8G8B8A8_SRGB
                else -> VkFormat.R8G8B8A8_UNORM
            }
            16 -> VkFormat.R16G16B16A16_UNORM
            else -> {
                logger.warn("Unsupported channel depth $channelDepth, using 16 bit.")
                VkFormat.R16G16B16A16_UINT
            }
        }

        createAndAddColorAttachmentInternal(name, format, attachmentWidth, attachmentHeight)

        return this
    }

    fun addUnsignedByteRBuffer(name: String, channelDepth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {

        val format = when (channelDepth) {
            8 -> VkFormat.R8_UNORM
            16 -> VkFormat.R16_UNORM
            else -> {
                logger.warn("Unsupported channel depth $channelDepth, using 16 bit.")
                VkFormat.R16_UNORM
            }
        }

        createAndAddColorAttachmentInternal(name, format, attachmentWidth, attachmentHeight)

        return this
    }

    fun addDepthBuffer(name: String, depth: Int, attachmentWidth: Int = width, attachmentHeight: Int = height): VulkanFramebuffer {

        val format = when (depth) {
            16 -> VkFormat.D16_UNORM
            24 -> VkFormat.D24_UNORM_S8_UINT
            32 -> VkFormat.D32_SFLOAT
            else -> {
                logger.warn("Unsupported channel depth $depth, using 32 bit.")
                VkFormat.D32_SFLOAT
            }
        }

        val bestSupportedFormat = getBestDepthFormat(format)[0]

        createAndAddDepthStencilAttachmentInternal(name, bestSupportedFormat, attachmentWidth, attachmentHeight)

        return this
    }

    fun addSwapchainAttachment(name: String, swapchain: Swapchain, index: Int): VulkanFramebuffer {

        val att = VulkanFramebufferAttachment()

        att.image = swapchain.images!![index]
        att.imageView = swapchain.imageViews!![index]
        att.type = AttachmentType.COLOR
        att.fromSwapchain = true

        val (loadOp, stencilLoadOp) = when {
            !shouldClear -> VkAttachmentLoadOp.LOAD to VkAttachmentLoadOp.LOAD
            else -> VkAttachmentLoadOp.CLEAR to VkAttachmentLoadOp.DONT_CARE
        }

        val initialImageLayout = VkImageLayout.UNDEFINED

        att.desc.apply {
            samples = VkSampleCount.`1_BIT`
            this.loadOp = loadOp
            storeOp = VkAttachmentStoreOp.STORE
            this.stencilLoadOp = stencilLoadOp
            stencilStoreOp = VkAttachmentStoreOp.DONT_CARE
            initialLayout = initialImageLayout
            finalLayout = VkImageLayout.PRESENT_SRC_KHR
            format = when {
                sRGB -> VkFormat.B8G8R8A8_SRGB
                else -> VkFormat.B8G8R8A8_UNORM
            }
        }
        attachments[name] = att

        return this
    }

    protected fun getAttachmentDescBuffer(): VkAttachmentDescription.Buffer {
        val descriptionBuffer = vk.AttachmentDescription(attachments.size)
        for (i in attachments.values.indices)
            descriptionBuffer[i] = attachments.values.elementAt(i).desc
        return descriptionBuffer
    }

    protected fun getAttachmentImageViews(): VkImageViewBuffer {
        val ivBuffer = appBuffer.longBuffer(attachments.size)
        attachments.values.forEachIndexed { i, it -> ivBuffer[i] = it.imageView }
        return ivBuffer
    }

    fun createRenderpassAndFramebuffer() {

        val colorDescs = vk.AttachmentReference(attachments.count { it.value.type == AttachmentType.COLOR })

        attachments.values.filter { it.type == AttachmentType.COLOR }.forEachIndexed { i, _ ->
            colorDescs[i].apply {
                attachment = i
                layout = VkImageLayout.COLOR_ATTACHMENT_OPTIMAL
            }
        }

        val depthDescs: VkAttachmentReference? = when {
            attachments.any { it.value.type == AttachmentType.DEPTH } -> vk.AttachmentReference {
                attachment = colorDescs.limit()
                layout = VkImageLayout.DEPTH_STENCIL_ATTACHMENT_OPTIMAL
            }
            else -> null
        }

        logger.trace("Subpass for has ${colorDescs.remaining()} color attachments")

        val subpass = vk.SubpassDescription {
            colorAttachments = colorDescs
            colorAttachmentCount = colorDescs.remaining()
            depthStencilAttachment = depthDescs
            pipelineBindPoint = VkPipelineBindPoint.GRAPHICS
        }
        val dependencyChain = vk.SubpassDependency(2).also {
            it[0].apply {
                srcSubpass = VK_SUBPASS_EXTERNAL
                dstSubpass = 0
                srcStageMask = VkPipelineStage.BOTTOM_OF_PIPE_BIT.i
                dstStageMask = VkPipelineStage.COLOR_ATTACHMENT_OUTPUT_BIT.i
                srcAccessMask = VkAccess.MEMORY_READ_BIT.i
                dstAccessMask = VkAccess.COLOR_ATTACHMENT_READ_BIT or VkAccess.COLOR_ATTACHMENT_WRITE_BIT
                dependencyFlags = VkDependency.BY_REGION_BIT.i
            }
            it[1].apply {
                srcSubpass = 0
                dstSubpass = VK_SUBPASS_EXTERNAL
                srcStageMask = VkPipelineStage.COLOR_ATTACHMENT_OUTPUT_BIT.i
                dstStageMask = VkPipelineStage.BOTTOM_OF_PIPE_BIT.i
                srcAccessMask = VkAccess.COLOR_ATTACHMENT_READ_BIT or VkAccess.COLOR_ATTACHMENT_WRITE_BIT
                dstAccessMask = VkAccess.MEMORY_READ_BIT.i
                dependencyFlags = VkDependency.BY_REGION_BIT.i
            }
        }

        val attachmentDescs = getAttachmentDescBuffer()
        val renderPassInfo = vk.RenderPassCreateInfo {
            attachments = attachmentDescs
            this.subpass = subpass
            dependencies = dependencyChain
        }
        renderPass = device.vulkanDevice.createRenderPass(renderPassInfo)

        logger.trace("Created renderpass $renderPass")

        val attachmentImageViews = getAttachmentImageViews()
        val fbinfo = vk.FramebufferCreateInfo {
            renderPass = this@VulkanFramebuffer.renderPass
            attachments = attachmentImageViews
            width = this@VulkanFramebuffer.width
            height = this@VulkanFramebuffer.height
            layers = 1
        }
        framebuffer = device.vulkanDevice.createFramebuffer(fbinfo)

        val samplerCreateInfo = vk.SamplerCreateInfo {
            magFilter = VkFilter.LINEAR
            minFilter = VkFilter.LINEAR
            mipmapMode = VkSamplerMipmapMode.LINEAR
            addressMode = VkSamplerAddressMode.CLAMP_TO_EDGE
            mipLodBias = 0f
            maxAnisotropy = 1f
            minLod = 0f
            maxLod = 1f
            borderColor = VkBorderColor.FLOAT_OPAQUE_WHITE
        }
        framebufferSampler = device.vulkanDevice.createSampler(samplerCreateInfo)

        initialized = true
    }

    override fun toString(): String = "VulkanFramebuffer"

    val id: Int
        get() = if (initialized) 0 else -1

    private fun getBestDepthFormat(preferredFormat: VkFormat): List<VkFormat> {
        // this iterates through the list of possible (though not all required formats)
        // and returns the first one that is possible to use as a depth buffer on the
        // given physical device.
        val props = vk.FormatProperties()
        val format = arrayOf(
            preferredFormat,
            VkFormat.D32_SFLOAT,
            VkFormat.D32_SFLOAT_S8_UINT,
            VkFormat.D24_UNORM_S8_UINT,
            VkFormat.D16_UNORM_S8_UINT,
            VkFormat.D16_UNORM
        ).filter {
            device.physicalDevice.getFormatProperties(it, props)

            props.optimalTilingFeatures has VkFormatFeature.DEPTH_STENCIL_ATTACHMENT_BIT
        }

        logger.debug("Using $format as depth format.")

        return format
    }

    fun colorAttachmentCount() = attachments.count { it.value.type == AttachmentType.COLOR }

    fun depthAttachmentCount() = attachments.count { it.value.type == AttachmentType.DEPTH }

    override fun close() {
        if (initialized) {

            attachments.values.forEach { it.close() }

            device.vulkanDevice.apply {

                destroyRenderPass(renderPass)

                destroySampler(framebufferSampler)

                destroyFramebuffer(framebuffer)
            }
            initialized = false
        }
    }
}

