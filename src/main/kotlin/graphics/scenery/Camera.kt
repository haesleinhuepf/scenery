package graphics.scenery

import cleargl.GLMatrix
import cleargl.GLVector
import com.jogamp.opengl.math.Quaternion
import kotlin.math.PI
import kotlin.math.tan

/**
 * Camera class that may be targeted or oriented
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @constructor Creates a new camera with default position and right-handed
 *  coordinate system.
 */
open class Camera : Node("Camera") {

    /** Enum class for camera projection types */
    enum class ProjectionType {
        Undefined, Perspective, Orthographic
    }

    /** Is the camera targeted? */
    var targeted = false
    /** Is this camera active? Setting one camera active will deactivate the others */
    var active = false

    /** Target, used if [targeted] is true */
    var target: GLVector = GLVector(0.0f, 0.0f, 0.0f)
    /** Forward vector of the camera, used if not targeted */
    var forward: GLVector = GLVector(0.0f, 0.0f, 1.0f)
    /** Up vector of the camera, used if not targeted */
    var up: GLVector = GLVector(0.0f, 1.0f, 0.0f)
    /** Right vector of the camera */
    var right: GLVector = GLVector(1.0f, 0.0f, 0.0f)
    /** FOV of the camera **/
    var fov: Float = 70.0f
    /** Z buffer near plane */
    var nearPlaneDistance = 0.05f
    /** Z buffer far plane location */
    var farPlaneDistance = 1000.0f
    /** delta T from the renderer */
    var deltaT = 0.0f
    /** Projection the camera uses */
    var projectionType: ProjectionType = ProjectionType.Undefined
    /** Width of the projection */
    var width: Float = 0.0f
    /** Height of the projection */
    var height: Float = 0.0f

    /** View matrix of the camera. Setting the view matrix will re-set the forward
     *  vector of the camera according to the given matrix.
     */
    override var view: GLMatrix = GLMatrix.getIdentity()
        set(m) {
            m.let {
                this.forward = GLVector(m.get(0, 2), m.get(1, 2), m.get(2, 2)).normalize() * -1.0f
                this.right = GLVector(m.get(0, 0), m.get(1, 0), m.get(2, 0)).normalize()
                this.up = GLVector(m.get(0, 1), m.get(1, 1), m.get(2, 1)).normalize()

                if(!targeted) {
                    this.target = this.position + this.forward
                }
            }
            field = m
        }

    /** Rotation of the camera. The rotation is applied after the view matrix */
    override var rotation: Quaternion = Quaternion(0.0f, 0.0f, 0.0f, 1.0f)
        set(q) {
            q.let {
                field = q
                val m = GLMatrix.fromQuaternion(q)
                this.forward = GLVector(m.get(0, 2), m.get(1, 2), m.get(2, 2)).normalize() * -1.0f
            }
        }

    init {
        this.nodeType = "Camera"
    }

    /**
     * Returns the current aspect ratio
     */
    fun aspectRatio(): Float {
        if(projectionType == ProjectionType.Undefined) {
            logger.warn("Querying aspect ratio but projection type is undefined")
            return 1.0f
        }

        if(width < 0.0001f || height < 0.0001f) {
            logger.warn("Width or height too small, returning 1.0f")
        }

        val scaleWidth = if(this is DetachedHeadCamera && this.tracker != null) {
            0.5f
        } else {
            1.0f
        }

        return (width*scaleWidth)/height
    }

    /**
     * Create a perspective projection camera
     */
    fun perspectiveCamera(fov: Float, width: Float, height: Float, nearPlaneLocation: Float = 0.1f, farPlaneLocation: Float = 1000.0f) {
        this.nearPlaneDistance = nearPlaneLocation
        this.farPlaneDistance = farPlaneLocation
        this.fov = fov

        this.width = width
        this.height = height

        this.projection = GLMatrix().setPerspectiveProjectionMatrix(
            this.fov / 180.0f * Math.PI.toFloat(),
            width / height,
            this.nearPlaneDistance,
            this.farPlaneDistance
        )

        this.projectionType = ProjectionType.Perspective
    }

    /**
     * Returns this camera's transformation matrix.
     */
    open fun getTransformation(): GLMatrix {
        val tr = GLMatrix.getTranslation(this.position * (-1.0f)).transpose()
        val r = GLMatrix.fromQuaternion(this.rotation)

        return r * tr
    }

    /**
     * Returns this camera's transformation matrix, including a
     * [preRotation] that is applied before the camera's transformation.
     */
    open fun getTransformation(preRotation: Quaternion): GLMatrix {
        val tr = GLMatrix.getTranslation(this.position * (-1.0f)).transpose()
        val r = GLMatrix.fromQuaternion(preRotation.mult(this.rotation))

        return r * tr
    }

    /**
     * Returns this camera's transformation for eye with index [eye].
     */
    open fun getTransformationForEye(eye: Int): GLMatrix {
        val tr = GLMatrix.getTranslation(this.position * (-1.0f)).transpose()
        val r = GLMatrix.fromQuaternion(this.rotation)

        return r * tr
    }

    /**
     * Multiplies this matrix with [GLMatrix] [rhs].
     */
    infix operator fun GLMatrix.times(rhs: GLMatrix): GLMatrix {
        val m = this.clone()
        m.mult(rhs)

        return m
    }

    /**
     * Transforms a 3D/4D vector from view space to world coordinates.
     *
     * @param v - The vector to be transformed into world space.
     * @return GLVector - [v] transformed into world space.
     */
    fun viewToWorld(v: GLVector): GLVector =
        this.view.inverse.mult(if(v.dimension == 3) {
            GLVector(v.x(), v.y(), v.z(), 1.0f)
        } else {
            v
        })

    /**
     * Transforms a 3D/4D vector from world space to view coordinates.
     *
     * @param v - The vector to be transformed into world space.
     * @return GLVector - [v] transformed into world space.
     */
    fun worldToView(v: GLVector): GLVector =
        this.view.mult(if(v.dimension == 3) {
            GLVector(v.x(), v.y(), v.z(), 1.0f)
        } else {
            v
        })

    /**
     * Transforms a 2D/3D [vector] from NDC coordinates to world coordinates.
     * If the vector is 2D, [nearPlaneDistance] is assumed for the Z value, otherwise
     * the Z value from the vector is taken.
     */
    @JvmOverloads fun viewportToWorld(vector: GLVector, offset: Float = 0.01f): GLVector {
        val unproject = projection.clone()
        unproject.mult(getTransformation())
        unproject.invert()

        var clipSpace = unproject.mult(when (vector.dimension) {
            1 -> GLVector(vector.x(), 1.0f, nearPlaneDistance + offset, 1.0f)
            2 -> GLVector(vector.x(), vector.y(), nearPlaneDistance + offset, 1.0f)
            3 -> GLVector(vector.x(), vector.y(), vector.z(), 1.0f)
            else -> vector
        })

        clipSpace = clipSpace.times(1.0f/clipSpace.w())
        return clipSpace.xyz()
    }

    /**
     * Returns the list of objects (as [Scene.RaycastResult]) under the screen space position
     * indicated by [x] and [y], sorted by their distance to the observer.
     */
    @JvmOverloads fun getNodesForScreenSpacePosition(x: Int, y: Int,
                                                       ignoredObjects: List<Class<*>> = emptyList(),
                                                       debug: Boolean = false): List<Scene.RaycastResult> {
        val view = (target - position).normalize()
        var h = view.cross(up).normalize()
        var v = h.cross(view)

        val fov = fov * Math.PI / 180.0f
        val lengthV = Math.tan(fov / 2.0).toFloat() * nearPlaneDistance
        val lengthH = lengthV * (width / height)

        v *= lengthV
        h *= lengthH

        val posX = (x - width / 2.0f) / (width / 2.0f)
        val posY = -1.0f * (y - height / 2.0f) / (height / 2.0f)

        val worldPos = position + view * nearPlaneDistance + h * posX + v * posY
        val worldDir = (worldPos - position).normalized

        val scene = getScene()
        if(scene == null) {
            logger.warn("No scene found for $this, returning empty list for raycast.")
            return emptyList()
        }

        return scene.raycast(worldPos, worldDir, ignoredObjects, debug)
    }

    /**
     * Returns true if the camera intersects this object's bounding box.
     */
    fun canSee(boundingBox: OrientedBoundingBox): Boolean {
        val z = (forward - position).normalize()
        val x = z.cross(up).normalize()
        val y = x.cross(z).normalize()

        val minView = boundingBox.min
        val maxView = boundingBox.max
        val mm1 = GLVector(maxView.x(), minView.y(), minView.z())
        val mm2 = GLVector(minView.x(), maxView.y(), minView.z())
        val mm3 = GLVector(minView.x(), maxView.y(), maxView.z())
        val mm4 = GLVector(maxView.x(), minView.y(), maxView.z())

        return tripodCanSee(x, y, z, minView)
            || tripodCanSee(x, y, z, maxView)
            || tripodCanSee(x, y, z, mm1)
            || tripodCanSee(x, y, z, mm2)
            || tripodCanSee(x, y, z, mm3)
            || tripodCanSee(x, y, z, mm4)
            || position.isInside(minView, maxView)
    }

    private fun GLVector.isInside(min: GLVector, max: GLVector): Boolean {
        return this.x() > min.x() && this.x() < max.x()
            && this.y() > min.y() && this.y() < max.y()
            && this.z() > min.z() && this.z() < max.z()
    }

    protected inline fun tripodCanSee(x: GLVector, y: GLVector, z: GLVector, p: GLVector): Boolean {
        val v = p - position
        val angle = tan(0.5f * fov * PI/180.0f)

        val pcz = v.times(z)
        if(pcz < nearPlaneDistance || pcz > farPlaneDistance) {
            logger.info("Plane test failed: $pcz vs $nearPlaneDistance/$farPlaneDistance")
            return false
        }

        val pcy = v.times(y)
        val h = angle * pcz
        if(pcy < -h || pcy > h) {
            logger.info("Height test failed: $pcy ∉ [-$h,$h]")
            return false
        }

        val pcx = v.times(x)
        val w = h * aspectRatio()
        if(pcx < -w || pcx > w) {
            logger.info("Width test failed: $pcx ∉ [-$w,$w]")
            return false
        }

        return true
    }
}

