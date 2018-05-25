package graphics.scenery.backends.vulkan

import ab.appBuffer
import glm_.buffer.free
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import graphics.scenery.GeometryType
import graphics.scenery.utils.LazyLogger
import vkk.*
import java.nio.IntBuffer
import java.util.*

/**
 * Vulkan Pipeline class.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class VulkanPipeline(val device: VulkanDevice, val pipelineCache: Long? = null) : AutoCloseable {
    private val logger by LazyLogger()

    var pipeline = HashMap<GeometryType, VulkanRenderer.Pipeline>()
    var descriptorSpecs = LinkedHashMap<String, VulkanShaderModule.UBOSpec>()

    val inputAssemblyState: VkPipelineInputAssemblyStateCreateInfo = cVkPipelineInputAssemblyStateCreateInfo {
        topology = VkPrimitiveTopology.TRIANGLE_LIST
    }

    val rasterizationState: VkPipelineRasterizationStateCreateInfo = cVkPipelineRasterizationStateCreateInfo {
        polygonMode = VkPolygonMode.FILL
        cullMode = VkCullMode.BACK_BIT.i
        frontFace = VkFrontFace.COUNTER_CLOCKWISE
        depthClampEnable = false
        rasterizerDiscardEnable = false
        depthBiasEnable = false
        lineWidth = 1f
    }
    val colorWriteMask: VkPipelineColorBlendAttachmentState = cVkPipelineColorBlendAttachmentState {
        blendEnable = false
        colorWriteMask = 0xF // this means RGBA writes
    }
    val colorBlendState: VkPipelineColorBlendStateCreateInfo = cVkPipelineColorBlendStateCreateInfo {
        attachment = colorWriteMask
    }

    val viewportState: VkPipelineViewportStateCreateInfo = cVkPipelineViewportStateCreateInfo {
        viewportCount = 1
        scissorCount = 1
    }
    val dynamicStates: IntBuffer = appBuffer.intBufferOf(VkDynamicState.VIEWPORT.i, VkDynamicState.SCISSOR.i)

    val dynamicState: VkPipelineDynamicStateCreateInfo = cVkPipelineDynamicStateCreateInfo {
        dynamicStates = this@VulkanPipeline.dynamicStates
    }

    var depthStencilState: VkPipelineDepthStencilStateCreateInfo = cVkPipelineDepthStencilStateCreateInfo {
        depthTestEnable = true
        depthWriteEnable = true
        depthCompareOp = VkCompareOp.LESS
        depthBoundsTestEnable = false
        minDepthBounds = 0f
        maxDepthBounds = 1f
        stencilTestEnable = false
    }
    val multisampleState: VkPipelineMultisampleStateCreateInfo = cVkPipelineMultisampleStateCreateInfo {
        sampleMask = null
        rasterizationSamples = VkSampleCount.`1_BIT`
    }
    val shaderStages = ArrayList<VulkanShaderModule>(2)

    fun addShaderStages(shaderModules: List<VulkanShaderModule>) {
        shaderStages.clear()

        shaderModules.forEach {
            shaderStages += it

            it.uboSpecs.forEach { uboName, ubo ->
                if (descriptorSpecs.containsKey(uboName)) {
                    descriptorSpecs[uboName]!!.members += ubo.members
                } else {
                    descriptorSpecs[uboName] = ubo
                }
            }
        }
    }

    fun createPipelines(renderpass: VulkanRenderpass, vulkanRenderpass: Long, vi: VkPipelineVertexInputStateCreateInfo,
                        descriptorSetLayouts: List<Long>, onlyForTopology: GeometryType? = null) {

        val setLayouts = appBuffer.longBufferOf(descriptorSetLayouts)

//        descriptorSetLayouts.forEachIndexed { i, layout -> setLayouts.put(i, layout) }

        val pushConstantRange = vk.PushConstantRange {
            offset = 0
            size = 4
            stageFlags = VkShaderStage.ALL.i
        }
        val pipelineLayoutCreateInfo = vk.PipelineLayoutCreateInfo().also {
            it.setLayouts = setLayouts
            it.pushConstantRange = pushConstantRange
        }
        val layout = device.vulkanDevice.createPipelineLayout(pipelineLayoutCreateInfo)

        val stages = vk.PipelineShaderStageCreateInfo(shaderStages.size)
        shaderStages.forEachIndexed { i, shaderStage ->
            stages[i] = shaderStage.shader
        }

        val pipelineCreateInfo = vk.GraphicsPipelineCreateInfo().also {
            it.layout = layout
            it.renderPass = vulkanRenderpass
            it.vertexInputState = vi
            it.inputAssemblyState = inputAssemblyState
            it.rasterizationState = rasterizationState
            it.colorBlendState = colorBlendState
            it.multisampleState = multisampleState
            it.viewportState = viewportState
            it.depthStencilState = depthStencilState
            it.stages = stages
            it.dynamicState = dynamicState
            it.flags = VkPipelineCreate.ALLOW_DERIVATIVES_BIT.i
            it.subpass = 0
        }
        onlyForTopology?.let { inputAssemblyState.topology = it.asVulkanTopology }

        val p = device.vulkanDevice.createGraphicsPipelines(pipelineCache ?: NULL, pipelineCreateInfo)

        val vkp = VulkanRenderer.Pipeline(p, layout)

        pipeline[GeometryType.TRIANGLES] = vkp
//        descriptorSpecs.sortBy { spec -> spec.set }

        logger.debug("Pipeline needs descriptor sets ${descriptorSpecs.keys.joinToString()}")

        if (onlyForTopology == null) {
            // create pipelines for other topologies as well
            GeometryType.values().filter { it != GeometryType.TRIANGLES }.forEach { topology ->

                inputAssemblyState.topology = topology.asVulkanTopology

                pipelineCreateInfo.apply {
                    this.inputAssemblyState = inputAssemblyState
                    basePipelineHandle = vkp.pipeline
                    basePipelineIndex = -1
                    flags = VkPipelineCreate.DERIVATIVE_BIT.i
                }
                val derivativeP = device.vulkanDevice.createGraphicsPipelines(pipelineCache ?: NULL, pipelineCreateInfo)

                val derivativePipeline = VulkanRenderer.Pipeline(derivativeP, layout)

                pipeline[topology] = derivativePipeline
            }
        }

        val derivatives = when (onlyForTopology) {
            null -> "Derivatives:" + pipeline.keys.joinToString()
            else -> "no derivatives, only ${pipeline.keys.first()}"
        }
        logger.debug("Created $this for renderpass ${renderpass.name} ($vulkanRenderpass) with pipeline layout $layout ($derivatives)")
    }

    fun getPipelineForGeometryType(type: GeometryType): VulkanRenderer.Pipeline {
        return pipeline[type] ?: pipeline[GeometryType.TRIANGLES]!!.also {
            logger.error("Pipeline $this does not contain a fitting pipeline for $type, return triangle pipeline")
        }
    }

    fun orderedDescriptorSpecs(): List<MutableMap.MutableEntry<String, VulkanShaderModule.UBOSpec>> {
        return descriptorSpecs.entries.sortedBy { it.value.binding }.sortedBy { it.value.set }
    }

    override fun toString(): String = "VulkanPipeline (${pipeline.map { "${it.key.name} -> ${String.format("0x%X", it.value.pipeline)}" }.joinToString()})"

    override fun close() {
        val removedLayouts = ArrayList<Long>()

        pipeline.forEach { _, pipeline ->
            device.vulkanDevice.destroyPipeline(pipeline.pipeline)

            if (!removedLayouts.contains(pipeline.layout)) {
                device.vulkanDevice.destroyPipelineLayout(pipeline.layout)
                removedLayouts += pipeline.layout
            }
        }

        inputAssemblyState.free()
        rasterizationState.free()
        depthStencilState.free()
        colorBlendState.attachments?.free()
        colorBlendState.free()
        viewportState.free()
        dynamicState.free()
        dynamicStates.free()
        multisampleState.free()
    }
}
