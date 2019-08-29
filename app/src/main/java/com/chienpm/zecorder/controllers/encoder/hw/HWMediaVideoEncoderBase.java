package com.chienpm.zecorder.controllers.encoder.hw;
/*
 * ScreenRecordingSample
 * Sample project to capture and save audio from internal and video from screen as MPEG4 file.
 *
 * Copyright (c) 2014-2016 saki t_saki@serenegiant.com
 *
 * File name: MediaVideoEncoderBase.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All files in the folder are under this Apache License, Version 2.0.
*/

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import com.chienpm.zecorder.controllers.encoder.MediaEncoder;
import com.chienpm.zecorder.controllers.encoder.MediaMuxerWrapper;
import com.chienpm.zecorder.controllers.settings.VideoSetting;

import java.io.IOException;
import java.util.List;

import static com.chienpm.zecorder.ui.utils.MyUtils.DEBUG;


public abstract class HWMediaVideoEncoderBase extends MediaEncoder {
	private static final String TAG = HWMediaVideoEncoderBase.class.getSimpleName();

	// parameters for recording
    private static final float BPP = 0.25f;

    protected final int mWidth;
    protected final int mHeight;

	public HWMediaVideoEncoderBase(final MediaMuxerWrapper muxer, final MediaEncoderListener listener, final int width, final int height, int orientation) {
		super(muxer, listener);
		if(orientation == VideoSetting.ORIENTATION_PORTRAIT) {
			mWidth = height;
			mHeight = width;
		}
		else{
			mWidth = width;
			mHeight = height;
		}
	}


	protected Surface prepare_surface_encoder(final String mime, final int frame_rate, final int bitrate)
		throws IOException, IllegalArgumentException {

		if (DEBUG) Log.i(TAG, String.format("prepare_surface_encoder:(%d,%d),mime=%s,frame_rate=%d,bitrate=%d", mWidth, mHeight, mime, frame_rate, bitrate));

		mTrackIndex = -1;
        mMuxerStarted = mIsEOS = false;

		this.formatVideoEncoder = FormatVideoEncoder.SURFACE;
		MediaCodecInfo encoder = chooseVideoEncoder(mime);

		if (encoder != null) {
			mMediaCodec = MediaCodec.createByCodecName(encoder.getName());
			if (this.formatVideoEncoder == FormatVideoEncoder.YUV420Dynamical) {
				this.formatVideoEncoder = chooseColorDynamically(encoder);
				if (this.formatVideoEncoder == null) {
					Log.e(TAG, "YUV420 dynamical choose failed");
					return null;
				}
			}
		} else {
			Log.e(TAG, "Valid encoder not found");
			return null;
		}

		if (DEBUG) Log.v(TAG, String.format("create_encoder_format:(%d,%d),mime=%s,frame_rate=%d,bitrate=%d", mWidth, mHeight, mime, frame_rate, bitrate));
		final MediaFormat format = MediaFormat.createVideoFormat(mime, mWidth, mHeight);
		//todo: change color format
		format.setInteger(MediaFormat.KEY_COLOR_FORMAT, this.formatVideoEncoder.getFormatCodec());	// API >= 18
		format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate > 0 ? bitrate : calcBitRate(frame_rate));
		format.setInteger(MediaFormat.KEY_FRAME_RATE, frame_rate);
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

		if (DEBUG) Log.i(TAG, "format: " + format);

//        mMediaCodec = MediaCodec.createEncoderByType(mime);
        mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // get Surface for encoder input
        // this method only can call between #configure and #start
        return mMediaCodec.createInputSurface();	// API >= 18
	}

	private FormatVideoEncoder chooseColorDynamically(MediaCodecInfo mediaCodecInfo) {
		for (int color : mediaCodecInfo.getCapabilitiesForType(CodecUtil.H264_MIME).colorFormats) {
			if (color == FormatVideoEncoder.YUV420PLANAR.getFormatCodec()) {
				return FormatVideoEncoder.YUV420PLANAR;
			} else if (color == FormatVideoEncoder.YUV420SEMIPLANAR.getFormatCodec()) {
				return FormatVideoEncoder.YUV420SEMIPLANAR;
			}
		}
		return null;
	}


	protected int calcBitRate(final int frameRate) {
		final int bitrate = (int)(BPP * frameRate * mWidth * mHeight);
		Log.i(TAG, String.format("bitrate=%5.2f[Mbps]", bitrate / 1024f / 1024f));
		return bitrate;
	}

    /**
     * select the first codec that match a specific MIME type
     * @param mimeType
     * @return null if no codec matched
     */
    @SuppressWarnings("deprecation")
	protected static final MediaCodecInfo selectVideoCodec(final String mimeType) {
    	if (DEBUG) Log.v(TAG, "selectVideoCodec:");

    	// get the list of available codecs
        final int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
        	final MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {	// skipp decoder
                continue;
            }
            // select first codec that match a specific MIME type and color format
            final String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                	if (DEBUG) Log.i(TAG, "codec:" + codecInfo.getName() + ",MIME=" + types[j]);
            		final int format = selectColorFormat(codecInfo, mimeType);
                	if (format > 0) {
                		return codecInfo;
                	}
                }
            }
        }
        return null;
    }

	/**
	 * Fixed hardware color format
	 */
	/**
	 * choose the video encoder by mime.
	 */
	private CodecUtil.Force force = CodecUtil.Force.FIRST_COMPATIBLE_FOUND;
	private FormatVideoEncoder formatVideoEncoder = FormatVideoEncoder.YUV420Dynamical;

	private MediaCodecInfo chooseVideoEncoder(String mime) {
		List<MediaCodecInfo> mediaCodecInfoList;
		if (force == CodecUtil.Force.HARDWARE) {
			mediaCodecInfoList = CodecUtil.getAllHardwareEncoders(mime);
		} else if (force == CodecUtil.Force.SOFTWARE) {
			mediaCodecInfoList = CodecUtil.getAllSoftwareEncoders(mime);
		} else {
			mediaCodecInfoList = CodecUtil.getAllEncoders(mime);
		}
		for (MediaCodecInfo mci : mediaCodecInfoList) {
			Log.i(TAG, String.format("VideoEncoder %s", mci.getName()));
			MediaCodecInfo.CodecCapabilities codecCapabilities = mci.getCapabilitiesForType(mime);
			for (int color : codecCapabilities.colorFormats) {
				Log.i(TAG, "Color supported: " + color);
				if (formatVideoEncoder == FormatVideoEncoder.SURFACE) {
					if (color == FormatVideoEncoder.SURFACE.getFormatCodec()) return mci;
				} else {
					//check if encoder support any yuv420 color
					if (color == FormatVideoEncoder.YUV420PLANAR.getFormatCodec()
							|| color == FormatVideoEncoder.YUV420SEMIPLANAR.getFormatCodec()) {
						return mci;
					}
				}
			}
		}
		return null;
	}

	/**
     * select color format available on specific codec and we can use.
     * @return 0 if no colorFormat is matched
     */
    protected static final int selectColorFormat(final MediaCodecInfo codecInfo, final String mimeType) {
		if (DEBUG) Log.i(TAG, "selectColorFormat: ");
    	int result = 0;
    	final MediaCodecInfo.CodecCapabilities caps;
    	try {
    		Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
    		caps = codecInfo.getCapabilitiesForType(mimeType);
    	} finally {
    		Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
    	}
        int colorFormat;
        for (int i = 0; i < caps.colorFormats.length; i++) {
        	colorFormat = caps.colorFormats[i];
            if (isRecognizedViewoFormat(colorFormat)) {
            	if (result == 0)
            		result = colorFormat;
                break;
            }
        }
        if (result == 0)
        	Log.e(TAG, "couldn't find a good color format for " + codecInfo.getName() + " / " + mimeType);
        return result;
    }

	/**
	 * color formats that we can use in this class
	 */
    protected static int[] recognizedFormats;
	static {
		recognizedFormats = new int[] {
//        	MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
//        	MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
//        	MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar,
        	MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
		};
	}

    protected static final boolean isRecognizedViewoFormat(final int colorFormat) {
		if (DEBUG) Log.i(TAG, "isRecognizedViewoFormat:colorFormat=" + colorFormat);
    	final int n = recognizedFormats != null ? recognizedFormats.length : 0;
    	for (int i = 0; i < n; i++) {
    		if (recognizedFormats[i] == colorFormat) {
    			return true;
    		}
    	}
    	return false;
    }

    @Override
    protected void signalEndOfInputStream() {
		if (DEBUG) Log.i(TAG, "sending EOS to encoder");
		mMediaCodec.signalEndOfInputStream();	// API >= 18
		mIsEOS = true;
	}

}
