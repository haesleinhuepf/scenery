package graphics.scenery.backends.vulkan

import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.*
import graphics.scenery.NodeMetadata
import graphics.scenery.utils.LazyLogger
import vkk.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Vulkan Object State class. Saves texture, UBO, pipeline and vertex buffer state.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class VulkanObjectState : NodeMetadata {
    protected val logger by LazyLogger()

    override val consumers: MutableList<String> = ArrayList(setOf("VulkanRenderer"))

    var initialized = false
    var isIndexed = false
    var indexOffset = 0L
    var indexCount = 0
    var vertexCount = 0
    var instanceCount = 1

    var vertexBuffers = ConcurrentHashMap<String, VulkanBuffer>()

    var UBOs = LinkedHashMap<String, Pair<VkDescriptorSet, VulkanUBO>>()

    var textures = ConcurrentHashMap<String, VulkanTexture>()

    var blendingHashCode = 0

    var defaultTexturesFor = HashSet<String>()

    var requiredDescriptorSets = HashMap<String, VkDescriptorSet>()

    var vertexInputType = VulkanRenderer.VertexDataKinds.PositionNormalTexcoord
    var vertexDescription: VulkanRenderer.VertexDescription? = null // TODO consider lateinit

    var textureDescriptorSet: VkDescriptorSet = NULL

    fun texturesToDescriptorSet(device: VulkanDevice, descriptorSetLayout: VkDescriptorSetLayout, descriptorPool: VkDescriptorPool, targetBinding: Int = 0): VkDescriptorSet {
        if (textureDescriptorSet == NULL) {
            val allocInfo = vk.DescriptorSetAllocateInfo(descriptorPool, descriptorSetLayout)
            textureDescriptorSet = device.vulkanDevice.allocateDescriptorSets(allocInfo)
        }

        val d = vk.DescriptorImageInfo(textures.size)
        val wd = vk.WriteDescriptorSet(textures.size)
        var i = 0

        textures.forEach { type, texture ->
            d[i].apply {
                imageView = texture.image!!.view
                sampler = texture.image!!.sampler
                imageLayout = VkImageLayout.SHADER_READ_ONLY_OPTIMAL
            }
            wd[i].apply {
                dstSet = textureDescriptorSet
                dstBinding = targetBinding + when {
                    type.contains("3D") -> 1
                    else -> 0
                }
                dstArrayElement = textureTypeToSlot(type)
                imageInfo_ = d[i]
                descriptorType = VkDescriptorType.COMBINED_IMAGE_SAMPLER
            }
            i++
        }

        device.vulkanDevice.updateDescriptorSets(wd)

        logger.debug("Creating texture descriptor {} set with 1 bindings, DSL={}", textureDescriptorSet.toHexString(), descriptorSetLayout.toHexString())
        return textureDescriptorSet
    }

    companion object {
        protected val logger by LazyLogger()

        fun textureTypeToSlot(type: String): Int {
            return when (type) {
                "ambient" -> 0
                "diffuse" -> 1
                "specular" -> 2
                "normal" -> 3
                "alphamask" -> 4
                "displacement" -> 5
                "3D-volume" -> 0
                else -> {
                    logger.warn("Unknown texture type: $type")
                    0
                }
            }
        }
    }
}
