package graphics.scenery.backends.vulkan

import glm_.L
import glm_.buffer.adr
import glm_.buffer.bufferBig
import glm_.buffer.free
import glm_.buffer.pos
import glm_.i
import graphics.scenery.utils.LazyLogger
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.VK10.*
import vkk.*
import java.nio.ByteBuffer

class VulkanBuffer(
    val device: VulkanDevice,
    val size: VkDeviceSize,
    val usage: VkBufferUsageFlags,
    val requestedMemoryProperties: VkMemoryPropertyFlags,
    wantAligned: Boolean = true) : AutoCloseable {

    protected val logger by LazyLogger()
    private var currentPosition = NULL
    private var currentPointer = NULL
    var alignment: VkDeviceSize = 256
        private set
    var memory: VkDeviceMemory = NULL
        private set
    var vulkanBuffer: VkBuffer = NULL
        private set
    var allocatedSize: VkDeviceSize = 0
        private set

    private var mapped = false

    var stagingBuffer = bufferBig(size.i)

    init {

        val bufferInfo = vk.BufferCreateInfo {
            usage = this@VulkanBuffer.usage
            size = this@VulkanBuffer.size
        }
        vulkanBuffer = device.vulkanDevice createBuffer bufferInfo
        val reqs = device.vulkanDevice getBufferMemoryRequirements vulkanBuffer

        alignment = reqs.alignment
        allocatedSize = when {
            wantAligned -> when {
                reqs.size % alignment == 0L -> reqs.size
                else -> reqs.size + alignment - (reqs.size % alignment)
            }
            else -> reqs.size
        }

        val allocInfo = vk.MemoryAllocateInfo {
            allocationSize = allocatedSize
            memoryTypeIndex(device.getMemoryType(reqs.memoryTypeBits, requestedMemoryProperties).first()) // TODO bug
        }

        memory = device.vulkanDevice allocateMemory allocInfo
        device.vulkanDevice.bindBufferMemory(vulkanBuffer, memory)
    }

    // TODO unused
    fun getPointerBuffer(size: Int): ByteBuffer {
        if (currentPointer == NULL)
            map()

        val buffer = memByteBuffer(currentPointer + currentPosition, size)
        currentPosition += size.L

        return buffer
    }

    fun getCurrentOffset(): Int {
        if (currentPosition % alignment != 0L) {
            currentPosition += alignment - (currentPosition % alignment)
            stagingBuffer.pos = currentPosition.i
        }
        return currentPosition.i
    }

    fun advance(align: Long = alignment): Int {
        val pos = stagingBuffer.pos
        val rem = pos % align

        if (rem != 0L) {
            val newPos = pos + align.i - rem.i
            stagingBuffer.pos = newPos
        }

        return stagingBuffer.pos
    }

    fun reset() {
        stagingBuffer.pos = 0
        stagingBuffer.limit(size.i)
        currentPosition = NULL
    }

    fun copyFrom(data: ByteBuffer) {
        device.vulkanDevice.mappingMemory(memory, 0, size) { dest ->
            memCopy(data.adr, dest, data.remaining().L)
        }
    }

    fun copyTo(dest: ByteBuffer) {
        device.vulkanDevice.mappingMemory(memory, 0, size) {src ->
            memCopy(src, dest.adr, dest.remaining().L)
        }
    }

    fun map(): Long {
        val dest = device.vulkanDevice.mapMemory(memory, 0, size)

        currentPointer = dest
        mapped = true
        return dest
    }

    fun mapIfUnmapped(): Long {
        if (currentPointer == NULL)
            currentPointer = map()

        return currentPointer
    }

    fun unmap() {
        mapped = false
        device.vulkanDevice.unmapMemory(memory)
    }

    fun copyFromStagingBuffer() {
        stagingBuffer.flip()
        copyFrom(stagingBuffer)
    }

    fun initialized(): Boolean = vulkanBuffer != NULL && memory != NULL

    override fun close() {
        logger.trace("Closing buffer $this ...")

        if (mapped)
            unmap()

        stagingBuffer.free()

        if (memory != NULL) {
            device.vulkanDevice.freeMemory(memory)
            memory = NULL
        }

        if (vulkanBuffer != NULL) {
            device.vulkanDevice.destroyBuffer(vulkanBuffer)
            vulkanBuffer = NULL
        }
    }
}
