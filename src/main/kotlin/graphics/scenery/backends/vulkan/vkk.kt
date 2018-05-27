package graphics.scenery.backends.vulkan

import cleargl.GLVector
import org.lwjgl.vulkan.*


// TODO jointToString

fun VkClearValue.color(v: GLVector) {
    color().float32(0, v.x())
    color().float32(1, v.y())
    color().float32(2, v.z())
    color().float32(3, v.w())
}
