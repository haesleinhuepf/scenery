package graphics.scenery.utils

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.avcodec.*
import org.bytedeco.javacpp.avformat.*
import org.bytedeco.javacpp.avutil.*
import org.bytedeco.javacpp.swscale
import org.bytedeco.javacpp.avfilter.*
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/**
 * H264 encoder class
 *
 * Experimental class for enabling movie recordings and streaming from within scenery, based on ffmpeg.
 *
 * @param[frameWidth] The width of the rendered picture. Will be rounded to the nearest multiple of 2 in the movie.
 * @param[frameHeight] The height of the rendered picture. Will be rounded to the nearest multiple of 2 in the movie.
 * @param[filename] The file name under which to save the movie. In case the system property `scenery.StreamVideo` is true,
 *      the frames are streamed via UDP multicast on the local IP, on port 3337 as MPEG transport stream.
 * @param[fps] The target frame rate for the movie.
 * @param[quality] The encoding quality, ranges from UltraFast to VerySlow
 * @param[flipV] Whether the encoded image should be flipped vertically
 * @param[flipH] Whether the encoded image should be flipped horizontally
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */

class H264Encoder(val frameWidth: Int, val frameHeight: Int, filename: String, fps: Int = 60,
                  val quality: EncodingQuality = EncodingQuality.UltraFast,
                  val flipV: Boolean = false, val flipH: Boolean = false) {
    protected val logger by LazyLogger()
    protected lateinit var frame: AVFrame
    protected lateinit var tmpframe: AVFrame

    protected lateinit var codec: AVCodec
    protected lateinit var codecContext: AVCodecContext
    protected var outputContext: AVFormatContext = AVFormatContext()
    protected lateinit var stream: AVStream

    protected var frameNum = 0L
    protected val timebase = AVRational().num(1).den(60)
    protected val framerate = AVRational().num(fps).den(1)

    protected var outputFile: String = filename
    protected var actualFrameWidth: Int = 0
    protected var actualFrameHeight: Int = 0

    protected var recordingStart = 0L

    val networked = System.getProperty("scenery.StreamVideo", "false")?.toBoolean() ?: false

    data class QueuedFrame(val bufferData: ByteBuffer?, val timestamp: Long)

    enum class EncodingQuality {
        UltraFast,
        Fast,
        Medium,
        Slow,
        VerySlow
    }

    fun EncodingQuality.toX264Preset(): String {
        return when (this) {
            H264Encoder.EncodingQuality.UltraFast -> "ultrafast"
            H264Encoder.EncodingQuality.Fast -> "fast"
            H264Encoder.EncodingQuality.Medium -> "medium"
            H264Encoder.EncodingQuality.Slow -> "slow"
            H264Encoder.EncodingQuality.VerySlow -> "veryslow"
        }
    }

    companion object {
        init {
            av_register_all()
            avcodec_register_all()
            avformat_network_init()
            avfilter_register_all()
        }
    }

    private fun Int.nearestWholeMultipleOf(divisor: Int): Int {
        var out = this.div(divisor)
        if (out == 0 && this > 0) {
            out++
        }

        return out * divisor
    }

    protected var scalingContext: swscale.SwsContext? = null
    protected var frameEncodingFailure = 0
    var encoding: Boolean = false
        private set
    var finished: Boolean = false
        private set
    protected var encoderQueue = ArrayDeque<QueuedFrame>()

    init {
        val latch = CountDownLatch(1)

        thread {
            if (logger.isDebugEnabled) {
                av_log_set_level(AV_LOG_TRACE)
            } else {
                av_log_set_level(AV_LOG_QUIET)
            }

            val url = System.getProperty("scenery.StreamingAddress")
                ?: "udp://${InetAddress.getLocalHost().hostAddress}:3337"

            val format = if (networked) {
                outputFile = url
                logger.info("Using network streaming, serving at $url")

                "rtp" to av_guess_format("mpegts", null, null)
            } else {
                "mp4" to av_guess_format("mp4", null, null)
            }

            var ret = avformat_alloc_output_context2(outputContext, format.second, format.first, outputFile)
            if (ret < 0) {
                logger.error("Could not allocate output context: $ret")
            }

            outputContext.video_codec_id(AV_CODEC_ID_H264)
            outputContext.audio_codec_id(AV_CODEC_ID_NONE)

            actualFrameWidth = frameWidth.nearestWholeMultipleOf(2)
            actualFrameHeight = frameHeight.nearestWholeMultipleOf(2)


            codec = avcodec_find_encoder(outputContext.video_codec_id())

            @Suppress("SENSELESS_COMPARISON")
            if (codec == null) {
                logger.error("Could not find H264 encoder")

                latch.countDown()
                return@thread
            }

            codecContext = avcodec_alloc_context3(codec)

            @Suppress("SENSELESS_COMPARISON")
            if (codecContext == null) {
                logger.error("Could not allocate video codecContext")

                latch.countDown()
                return@thread
            }

            codecContext.codec_id(AV_CODEC_ID_H264)
            codecContext.bit_rate(4000000)
            codecContext.width(actualFrameWidth)
            codecContext.height(actualFrameHeight)
            codecContext.time_base(timebase)
            codecContext.framerate(framerate)
            codecContext.gop_size(10)
            codecContext.max_b_frames(1)
            codecContext.pix_fmt(AV_PIX_FMT_YUV420P)
            codecContext.codec_tag(0)
            codecContext.codec_type(AVMEDIA_TYPE_VIDEO)

            val filters = if(flipV || flipH) {
                initializeFlippingFilters(actualFrameWidth, actualFrameHeight, codecContext.pix_fmt(), flipV, flipH)
            } else {
                null
            }

            if (networked) {
                codecContext.flags(CODEC_FLAG_GLOBAL_HEADER)
            }

            if (outputContext.oformat().flags() and AVFMT_GLOBALHEADER == 1) {
                logger.debug("Output format requires global format header")
                codecContext.flags(codecContext.flags() or CODEC_FLAG_GLOBAL_HEADER)
            }

            av_opt_set(codecContext.priv_data(), "preset", quality.toX264Preset(), 0)
            av_opt_set(codecContext.priv_data(), "tune", "zerolatency", 0)
            av_opt_set(codecContext.priv_data(), "repeat-headers", "1", 0)

            val cores = Runtime.getRuntime().availableProcessors()
            val threads = when (cores) {
                1 -> 1
                in 2..3 -> 2
                else -> 4
            }

            av_opt_set(codecContext.priv_data(), "threads", threads.toString(), 0)

            ret = avcodec_open2(codecContext, codec, AVDictionary())
            if (ret < 0) {
                logger.error("Could not open codec: ${ffmpegErrorString(ret)}")

                latch.countDown()
                return@thread
            }

            stream = avformat_new_stream(outputContext, codec)

            @Suppress("SENSELESS_COMPARISON")
            if (stream == null) {
                logger.error("Could not allocate stream")

                latch.countDown()
                return@thread
            }

            stream.time_base(timebase)
            stream.id(outputContext.nb_streams() - 1)
            stream.r_frame_rate(codecContext.framerate())

            logger.debug("Stream ID will be ${stream.id()}")

            frame = av_frame_alloc()
            frame.format(codecContext.pix_fmt())
            frame.width(codecContext.width())
            frame.height(codecContext.height())

            tmpframe = av_frame_alloc()
            tmpframe.format(codecContext.pix_fmt())
            tmpframe.width(codecContext.width())
            tmpframe.height(codecContext.height())

            outputContext.streams(0, stream)

            ret = avcodec_parameters_from_context(stream.codecpar(), codecContext)
            if (ret < 0) {
                logger.error("Could not get codec parameters")

                latch.countDown()
                return@thread
            }

            ret = av_frame_get_buffer(frame, 32)
            if (ret < 0) {
                logger.error("Could not allocate frame data")

                latch.countDown()
                return@thread
            }

            av_dump_format(outputContext, 0, outputFile, 1)

            if (outputContext.oformat().flags() and AVFMT_NOFILE == 0) {
                val pb = AVIOContext(null)
                ret = avio_open(pb, outputFile, AVIO_FLAG_WRITE)
                outputContext.pb(pb)

                if (ret < 0) {
                    logger.error("Failed to open output file $outputFile: $ret")

                    latch.countDown()
                    return@thread
                }
            } else {
                logger.debug("Not opening file as not required by outputContext")
            }

            logger.info("Writing movie to $outputFile, with format ${String(outputContext.oformat().long_name().stringBytes)} ($threads threads)")

//        Don't use SDP files for the moment
//        if(networked) {
//            val buffer = ByteArray(1024, { 0 })
//            av_sdp_create(outputContext, 1, buffer, buffer.size)
//
//            File("$filename.sdp").bufferedWriter().use { out ->
//                logger.info("SDP size: ${String(buffer).length}")
//                out.write(String(buffer).substringBefore('\u0000'))
//            }
//        }

            ret = avformat_write_header(outputContext, AVDictionary())

            if (ret < 0) {
                logger.error("Failed to write header: ${ffmpegErrorString(ret)}")

                latch.countDown()
                return@thread
            }

            encoding = true
            recordingStart = System.nanoTime()
            latch.countDown()

            while (encoding || encoderQueue.size > 0) {
                if (encoderQueue.size == 0) {
                    Thread.sleep(1)
                    continue
                }

                val encodeStart = System.nanoTime()
                // poll first element in the queue and use it
                val queuedFrame = encoderQueue.pollFirst()
                val data = queuedFrame.bufferData

                if (frameEncodingFailure != 0) {
                    encoding = false
                }

                if (scalingContext == null) {
                    scalingContext = swscale.sws_getContext(
                        frameWidth, frameHeight, AV_PIX_FMT_BGRA,
                        actualFrameWidth, actualFrameHeight, AV_PIX_FMT_YUV420P, swscale.SWS_BICUBIC,
                        null, null, DoublePointer())
                }

                av_frame_make_writable(tmpframe)
                av_frame_make_writable(frame)

                val sfill = System.nanoTime()
                av_image_fill_arrays(tmpframe.data(), tmpframe.linesize(), BytePointer(data), AV_PIX_FMT_BGRA, frameWidth, frameHeight, 1)
                val dfill = (System.nanoTime() - sfill)/10e5
                logger.info("fill of $frameWidth/$frameHeight->$actualFrameWidth/$actualFrameHeight took $dfill ms")

                val packet = AVPacket()
                av_init_packet(packet)

                ret = if (data != null) {
                    tmpframe.pts(frameNum)
                    frame.pts(frameNum)

                    val ss = System.nanoTime()
                    swscale.sws_scale(scalingContext,
                        tmpframe.data(),
                        tmpframe.linesize(), 0, frameHeight,
                        frame.data(), frame.linesize())
                    val ds = (System.nanoTime() - ss)/10e5
                    logger.info("swscale took $ds ms with linesize=${tmpframe.linesize()}, fh=$frameHeight, f.linesize=${frame.linesize()}")

                    val sf = System.nanoTime()
                    filters?.let { f ->
                        av_buffersrc_add_frame(f.first, frame)
                        av_buffersink_get_frame(f.second, frame)
                    }
                    val df = (System.nanoTime() - sf)/10e5
                    logger.info("filter took $df ms")

                    val ssf = System.nanoTime()
                    val r = avcodec_send_frame(codecContext, frame)
                    val dsf = (System.nanoTime() - ssf)/10e5
                    logger.info("send took $dsf ms")
                    r
                } else {
                    avcodec_send_frame(codecContext, null)
                }

                val senc = System.nanoTime()
                while (ret >= 0) {
                    ret = avcodec_receive_packet(codecContext, packet)

                    if (ret == -11 /* AVERROR_EAGAIN */ || ret == AVERROR_EOF || ret == -35 /* also AVERROR_EAGAIN -.- */) {
                        frameNum++
                        continue
                    } else if (ret < 0) {
                        logger.error("Error encoding frame $frameNum: ${ffmpegErrorString(ret)} ($ret)")
                        frameEncodingFailure = ret
                        continue
                    }

                    packet.stream_index(0)
                    packet.pts(queuedFrame.timestamp)
                    packet.dts(queuedFrame.timestamp)
                    av_packet_rescale_ts(packet, AVRational().num(1).den(1000), stream.time_base())

                    ret = av_write_frame(outputContext, packet)

                    if (ret < 0) {
                        logger.error("Error writing frame $frameNum: ${ffmpegErrorString(ret)}")
                    }
                }
                val denc = (System.nanoTime() - senc)/10e5
                logger.info("encode took $denc ms")

                val encodeDuration = (System.nanoTime() - encodeStart)/10e5
                logger.debug("Encoded frame $frameNum in $encodeDuration ms")

                frameNum++
            }

            // done encoding here, write file and trailer
            av_write_trailer(outputContext)
            avio_closep(outputContext.pb())
            avformat_free_context(outputContext)

            logger.info("Finished recording $outputFile, wrote $frameNum frames.")
            finished = true
        }

        latch.await()
    }

    protected fun initializeFlippingFilters(width: Int, height: Int, pixelformat: Int, flipV: Boolean, flipH: Boolean): Pair<AVFilterContext, AVFilterContext>? {
        val vflip = avfilter_get_by_name("vflip")
        val hflip = avfilter_get_by_name("hflip")

        val src = avfilter_get_by_name("buffer")
        val sink = avfilter_get_by_name("buffersink")

        val graphContext = avfilter_graph_alloc()
        val filterContextH = AVFilterContext()
        val filterContextV = AVFilterContext()
        val srcContext = AVFilterContext()
        val sinkContext = AVFilterContext()

        val args = "video_size=${width}x$height:pix_fmt=$pixelformat:time_base=${timebase.num()}/${timebase.den()}:pixel_aspect=1/1"

        if (avfilter_graph_create_filter(srcContext, src, "in", args, null, graphContext) < 0) {
            logger.error("Could not create in filter")
            return null
        }

        if (avfilter_graph_create_filter(sinkContext, sink, "out", null, null, graphContext) < 0) {
            logger.error("Could not create out filter")
            return null
        }

        if(flipV) {
            if (avfilter_graph_create_filter(filterContextV, vflip, "vflip", null, null, graphContext) < 0) {
                logger.error("Could not create vflip filter")
                return null
            }
        }

        if(flipH) {
            if (avfilter_graph_create_filter(filterContextH, hflip, "hflip", null, null, graphContext) < 0) {
                logger.error("Could not create hflip filter")
                return null
            }
        }

        if (flipH && flipV) {
            avfilter_link(srcContext, 0, filterContextV, 0)
            avfilter_link(filterContextV, 0, filterContextH, 0)
            avfilter_link(filterContextH, 0, sinkContext, 0)
        }

        if (flipH && !flipV) {
            avfilter_link(srcContext, 0, filterContextH, 0)
            avfilter_link(filterContextH, 0, sinkContext, 0)
        }

        if (!flipH && flipV) {
            avfilter_link(srcContext, 0, filterContextV, 0)
            avfilter_link(filterContextV, 0, sinkContext, 0)
        }

        if(avfilter_graph_config(graphContext, null) < 0) {
            logger.error("Could not create filter graph")
            return null
        }

        return srcContext to sinkContext
    }

    fun encodeFrame(data: ByteBuffer?) {
        val pressure = encoderQueue.size
        if(pressure < 3) {
            encoderQueue.addLast(QueuedFrame(data, getCurrentTimestamp()))
        } else {
            logger.warn("Encoder pressure too high, skipping frame")
        }
    }

    fun finish() {
        encodeFrame(null)
        encoding = false
    }

    fun close() {
        if(encoding) {
            logger.info("Closing down, flushing encoder queue.")
            encoderQueue.clear()
            finish()

            while(!finished) {
                Thread.sleep(50)
            }

            logger.info("Encoder closed.")
        }
    }

    private fun getCurrentTimestamp(): Long {
        return ((System.nanoTime() - recordingStart)/10e5).roundToLong()
    }

    private fun ffmpegErrorString(returnCode: Int): String {
        val buffer = ByteArray(1024, { _ -> 0 })
        av_make_error_string(buffer, buffer.size * 1L, returnCode)

        return String(buffer, 0, buffer.indexOfFirst { it == 0.toByte() })
    }
}
