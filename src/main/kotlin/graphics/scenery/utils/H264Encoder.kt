package graphics.scenery.utils

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.avcodec.*
import org.bytedeco.javacpp.avformat.*
import org.bytedeco.javacpp.avutil.*
import org.bytedeco.javacpp.swscale
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.*
import kotlin.concurrent.thread

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
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */

class H264Encoder(val frameWidth: Int, val frameHeight: Int, filename: String, fps: Int = 60) {
    protected val logger by LazyLogger()
    protected lateinit var frame: AVFrame
    protected lateinit var tmpframe: AVFrame

    protected lateinit var codec: AVCodec
    protected lateinit var codecContext: AVCodecContext
    protected var outputContext: AVFormatContext = AVFormatContext()
    protected lateinit var stream: AVStream

    protected var frameNum = 0L
    protected val timebase = AVRational().num(1).den(fps)
    protected val framerate = AVRational().num(fps).den(1)

    protected var outputFile: String = filename
    protected var actualFrameWidth: Int = 0
    protected var actualFrameHeight: Int = 0

    val networked = System.getProperty("scenery.StreamVideo", "false")?.toBoolean() ?: false

    data class QueuedFrame(val bufferData: ByteBuffer?)

    companion object {
        init {
            av_register_all()
            avcodec_register_all()
            avformat_network_init()
        }
    }

    private fun Int.nearestWholeMultipleOf(divisor: Int): Int {
        var out = this.div(divisor)
        if(out == 0 && this > 0) {
            out++
        }

        return out * divisor
    }

    protected var scalingContext: swscale.SwsContext? = null
    protected var frameEncodingFailure = 0
    var encoding: Boolean = false
        private set
    protected var encoderQueue = ArrayDeque<QueuedFrame>()

    init {
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
            if (codec == null) {
                logger.error("Could not find H264 encoder")
            }

            codecContext = avcodec_alloc_context3(codec)
            if (codecContext == null) {
                logger.error("Could not allocate video codecContext")
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

            if (networked) {
                codecContext.flags(CODEC_FLAG_GLOBAL_HEADER)
            }

            if (outputContext.oformat().flags() and AVFMT_GLOBALHEADER == 1) {
                logger.debug("Output format requires global format header")
                codecContext.flags(codecContext.flags() or CODEC_FLAG_GLOBAL_HEADER)
            }

            av_opt_set(codecContext.priv_data(), "preset", "ultrafast", 0)
            av_opt_set(codecContext.priv_data(), "tune", "zerolatency", 0)
            av_opt_set(codecContext.priv_data(), "repeat-headers", "1", 0)
            av_opt_set(codecContext.priv_data(), "threads", "4", 0)

            ret = avcodec_open2(codecContext, codec, AVDictionary())
            if (ret < 0) {
                logger.error("Could not open codec: ${ffmpegErrorString(ret)}")
            }

            stream = avformat_new_stream(outputContext, codec)
            if (stream == null) {
                logger.error("Could not allocate stream")
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
            }

            ret = av_frame_get_buffer(frame, 32)
            if (ret < 0) {
                logger.error("Could not allocate frame data")
            }

            av_dump_format(outputContext, 0, outputFile, 1)

            if (outputContext.oformat().flags() and AVFMT_NOFILE == 0) {
                val pb = AVIOContext(null)
                ret = avio_open(pb, outputFile, AVIO_FLAG_WRITE)
                outputContext.pb(pb)

                if (ret < 0) {
                    logger.error("Failed to open output file $outputFile: $ret")
                }
            } else {
                logger.debug("Not opening file as not required by outputContext")
            }

            logger.info("Writing movie to $outputFile, with format ${String(outputContext.oformat().long_name().stringBytes)}")

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
            }

            encoding = true

            while (encoding) {
                if (encoderQueue.size == 0) {
                    Thread.sleep(20)
                    continue
                }

                // poll first element in the queue and use it
                val data = encoderQueue.pollFirst().bufferData

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

                av_image_fill_arrays(tmpframe.data(), tmpframe.linesize(), BytePointer(data), AV_PIX_FMT_BGRA, frameWidth, frameHeight, 1)

                val packet = AVPacket()
                av_init_packet(packet)

                ret = if (data != null) {
                    tmpframe.pts(frameNum)
                    frame.pts(frameNum)

                    swscale.sws_scale(scalingContext,
                        tmpframe.data(),
                        tmpframe.linesize(), 0, frameHeight,
                        frame.data(), frame.linesize())

                    avcodec_send_frame(codecContext, frame)
                } else {
                    avcodec_send_frame(codecContext, null)
                }

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

                    av_packet_rescale_ts(packet, timebase, stream.time_base())

                    ret = av_write_frame(outputContext, packet)

                    if (ret < 0) {
                        logger.error("Error writing frame $frameNum: ${ffmpegErrorString(ret)}")
                    }
                }

                logger.trace("Encoded frame $frameNum")

                frameNum++
            }

            // done encoding here, write file and trailer
            av_write_trailer(outputContext)
            avio_closep(outputContext.pb())
            avformat_free_context(outputContext)

            logger.info("Finished recording $outputFile, wrote $frameNum frames.")
        }
    }

    fun encodeFrame(data: ByteBuffer?) {
        encoderQueue.addLast(QueuedFrame(data))
    }

    fun finish() {
        encodeFrame(null)
        encoding = false
    }

    private fun ffmpegErrorString(returnCode: Int): String {
        val buffer = ByteArray(1024, { _ -> 0 })
        av_make_error_string(buffer, buffer.size * 1L, returnCode)

        return String(buffer, 0, buffer.indexOfFirst { it == 0.toByte() })
    }
}
