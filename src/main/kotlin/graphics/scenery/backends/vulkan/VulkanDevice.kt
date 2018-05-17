package graphics.scenery.backends.vulkan

import graphics.scenery.utils.LazyLogger
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.memUTF8
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import vkk.*
import java.util.ArrayList

class VulkanDevice(
    val instance: VkInstance,
    val physicalDevice: VkPhysicalDevice,
    val deviceData: DeviceData, extensionsQuery: (VkPhysicalDevice) -> List<String> = { listOf() }, validationLayers: List<String> = listOf()) {

    val logger by LazyLogger()
    val memoryProperties = VkPhysicalDeviceMemoryProperties.calloc()
    val vulkanDevice: VkDevice
    val queueIndices: QueueIndices
    val extensions = ArrayList<String>()

    enum class DeviceType { Unknown, Other, IntegratedGPU, DiscreteGPU, VirtualGPU, CPU }
    data class DeviceData(val vendor: String, val name: String, val driverVersion: String, val apiVersion: String, val type: DeviceType)
    data class QueueIndices(val presentQueue: Int, val graphicsQueue: Int, val computeQueue: Int)

    init {

        val queueProps = physicalDevice.queueFamilyProperties

        var graphicsQueueFamilyIndex = 0
        var computeQueueFamilyIndex = 0
        val presentQueueFamilyIndex = 0
        var index = 0

        while (index < queueProps.size) {
            if (queueProps[index].queueFlags has VkQueueFlag.GRAPHICS_BIT)
                graphicsQueueFamilyIndex = index

            if (queueProps[index].queueFlags has VkQueueFlag.COMPUTE_BIT)
                computeQueueFamilyIndex = index

            index++
        }

        val queueCreateInfo = vk.DeviceQueueCreateInfo {
            queueFamilyIndex = graphicsQueueFamilyIndex
            queuePriority = 0f
        }
        val extensionsRequested = extensionsQuery(physicalDevice)
        logger.debug("Requested extensions: ${extensionsRequested.joinToString()} ${extensionsRequested.size}")

        // allocate enough pointers for required extensions, plus the swapchain extension
        val extensions = ArrayList(extensionsRequested)
        extensions += KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME

        if (validationLayers.isNotEmpty())
            logger.warn("Enabled Vulkan API validations. Expect degraded performance.")

        val enabledFeatures = vk.PhysicalDeviceFeatures {
            samplerAnisotropy = true
            largePoints = true
        }
        val deviceCreateInfo = vk.DeviceCreateInfo {
            this.queueCreateInfo = queueCreateInfo
            enabledExtensionNames = extensions
            enabledLayerNames = validationLayers
            this.enabledFeatures = enabledFeatures
        }
        logger.debug("Creating device...")
        vulkanDevice = physicalDevice.createDevice(deviceCreateInfo)
        logger.debug("Device successfully created.")

        physicalDevice.getMemoryProperties(memoryProperties)

        VulkanRenderer.DeviceAndGraphicsQueueFamily(vulkanDevice, graphicsQueueFamilyIndex, computeQueueFamilyIndex, presentQueueFamilyIndex, memoryProperties)

        queueIndices = QueueIndices(
            presentQueue = presentQueueFamilyIndex,
            computeQueue = computeQueueFamilyIndex,
            graphicsQueue = graphicsQueueFamilyIndex)

        extensions += extensionsQuery(physicalDevice)
        extensions += KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME

        logger.debug("Created logical Vulkan device on ${deviceData.vendor} ${deviceData.name}")
    }

    fun getMemoryType(typeBits: Int, flags: VkMemoryPropertyFlags): List<Int> {
        var bits = typeBits
        val types = ArrayList<Int>(5)

        for (i in 0 until memoryProperties.memoryTypeCount()) {
            if (bits and 1 == 1) {
                if ((memoryProperties.memoryTypes(i).propertyFlags() and flags) == flags) {
                    types.add(i)
                }
            }

            bits = bits shr 1
        }

        if (types.isEmpty()) {
            logger.warn("Memory type $flags not found for device $this (${vulkanDevice.address().toHexString()}")
        }

        return types
    }

    fun createCommandPool(queueNodeIndex: Int): Long {
        return stackPush().use { stack ->
            val cmdPoolInfo = VkCommandPoolCreateInfo.callocStack(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(queueNodeIndex)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)

            val pCmdPool = stack.callocLong(1)
            val err = vkCreateCommandPool(vulkanDevice, cmdPoolInfo, null, pCmdPool)
            val commandPool = pCmdPool.get(0)

            if (err != VK_SUCCESS) {
                throw RuntimeException("Failed to create command pool: " + VU.translate(err))
            }

            commandPool
        }
    }

    fun destroyCommandPool(commandPool: Long) {
        vkDestroyCommandPool(vulkanDevice, commandPool, null)
    }

    override fun toString(): String {
        return "${deviceData.vendor} ${deviceData.name}"
    }

    fun close() {
        logger.debug("Closing device ${deviceData.vendor} ${deviceData.name}...")
        vkDeviceWaitIdle(vulkanDevice)
        vkDestroyDevice(vulkanDevice, null)
        logger.debug("Device closed.")

        memoryProperties.free()
    }

    companion object {
        val logger by LazyLogger()

        data class DeviceWorkaround(val filter: (DeviceData) -> Boolean, val description: String, val workaround: (DeviceData) -> Any)


        val deviceWorkarounds = listOf(
            DeviceWorkaround(
                { it.vendor == "Nvidia" && it.driverVersion.substringBefore(".").toInt() >= 396 },
                "Nvidia 396.xx series drivers are unsupported due to crashing bugs in the driver") {
                if (System.getenv("__GL_NextGenCompiler") == null) {
                    logger.warn("The graphics driver version you are using (${it.driverVersion}) contains a bug that prevents scenery's Vulkan renderer from functioning correctly.")
                    logger.warn("Please set the environment variable __GL_NextGenCompiler to 0 and restart the application to work around this issue.")
                    logger.warn("For this session, scenery will fall back to the OpenGL renderer in 20 seconds.")
                    Thread.sleep(20000)

                    throw RuntimeException("Bug in graphics driver, falling back to OpenGL")
                }
            }
        )

        private fun toDeviceType(vkDeviceType: Int): DeviceType {
            return when (vkDeviceType) {
                0 -> DeviceType.Other
                1 -> DeviceType.IntegratedGPU
                2 -> DeviceType.DiscreteGPU
                3 -> DeviceType.VirtualGPU
                4 -> DeviceType.CPU
                else -> DeviceType.Unknown
            }
        }

        private fun vendorToString(vendor: Int): String =
            when (vendor) {
                0x1002 -> "AMD"
                0x10DE -> "Nvidia"
                0x8086 -> "Intel"
                else -> "(Unknown vendor)"
            }

        private fun decodeDriverVersion(version: Int) =
            Triple(
                version and 0xFFC00000.toInt() shr 22,
                version and 0x003FF000 shr 12,
                version and 0x00000FFF
            )

        private fun driverVersionToString(version: Int) =
            decodeDriverVersion(version).toList().joinToString(".")

        @JvmStatic
        fun fromPhysicalDevice(instance: VkInstance, physicalDeviceFilter: (Int, DeviceData) -> Boolean,
                               additionalExtensions: (VkPhysicalDevice) -> List<String> = { listOf() },
                               validationLayers: List<String> = listOf()): VulkanDevice {
            return stackPush().use { stack ->

                val physicalDeviceCount = VU.getInt("Enumerate physical devices",
                    { vkEnumeratePhysicalDevices(instance, this, null) })

                if (physicalDeviceCount < 1) {
                    throw RuntimeException("No Vulkan-compatible devices found!")
                }

                val physicalDevices = VU.getPointers("Getting Vulkan physical devices", physicalDeviceCount,
                    { vkEnumeratePhysicalDevices(instance, intArrayOf(physicalDeviceCount), this) })

                var devicePreference = 0

                logger.info("Physical devices: ")
                val properties: VkPhysicalDeviceProperties = VkPhysicalDeviceProperties.callocStack(stack)
                val deviceList = ArrayList<DeviceData>(10)

                for (i in 0 until physicalDeviceCount) {
                    val device = VkPhysicalDevice(physicalDevices.get(i), instance)

                    vkGetPhysicalDeviceProperties(device, properties)

                    val deviceData = DeviceData(
                        vendor = vendorToString(properties.vendorID()),
                        name = properties.deviceNameString(),
                        driverVersion = driverVersionToString(properties.driverVersion()),
                        apiVersion = driverVersionToString(properties.apiVersion()),
                        type = toDeviceType(properties.deviceType()))

                    if (physicalDeviceFilter.invoke(i, deviceData)) {
                        devicePreference = i
                    }

                    deviceList.add(deviceData)
                }

                deviceList.forEachIndexed { i, device ->
                    val selected = if (devicePreference == i) {
                        "(selected)"
                    } else {
                        ""
                    }

                    logger.info("  $i: ${device.vendor} ${device.name} (${device.type}, driver version ${device.driverVersion}, Vulkan API ${device.apiVersion}) $selected")
                }

                val selectedDevice = physicalDevices.get(devicePreference)
                val selectedDeviceData = deviceList[devicePreference]

                if (System.getProperty("scenery.DisableDeviceWorkarounds", "false")?.toBoolean() != true) {
                    deviceWorkarounds.forEach {
                        if (it.filter.invoke(selectedDeviceData)) {
                            logger.warn("Workaround activated: ${it.description}")
                            it.workaround.invoke(selectedDeviceData)
                        }
                    }
                } else {
                    logger.warn("Device-specific workarounds disabled upon request, expect weird things to happen.")
                }

                val physicalDevice = VkPhysicalDevice(selectedDevice, instance)

                physicalDevices.free()

                VulkanDevice(instance, physicalDevice, selectedDeviceData, additionalExtensions, validationLayers)
            }
        }
    }
}
