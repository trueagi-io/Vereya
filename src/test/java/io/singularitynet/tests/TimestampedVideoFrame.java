package io.singularitynet.tests;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Parsed video/colourmap frame + metadata header. */
public final class TimestampedVideoFrame {
    public final float[] calibrationMatrix; // 4x4, column-major
    public final float[] modelViewMatrix;   // 4x4, column-major

    public final double timestamp; // seconds
    public final FrameType frametype;

    public final byte[] _pixels; // raw bytes, BGR(A) as sent

    public final float pitch;
    public final float yaw;
    public final float xPos;
    public final float yPos;
    public final float zPos;

    public final int iHeight;
    public final int iWidth;
    public final int iCh;

    public TimestampedVideoFrame(TimestampedByteVector message, FrameType frametype) {
        this.timestamp = message.getTimestampSeconds();
        this.frametype = frametype;
        ByteBuffer bb = ByteBuffer.wrap(message.data).order(ByteOrder.BIG_ENDIAN);
        if (bb.remaining() < 4) throw new IllegalArgumentException("payload too small");
        int jsonLen = bb.getInt();
        if (jsonLen < 0 || jsonLen > bb.remaining()) throw new IllegalArgumentException("invalid json length: " + jsonLen);
        byte[] js = new byte[jsonLen];
        bb.get(js);
        JSONObject hdr = new JSONObject(new String(js, StandardCharsets.UTF_8));

        this.xPos = (float) hdr.optDouble("x", 0.0);
        this.yPos = (float) hdr.optDouble("y", 0.0);
        this.zPos = (float) hdr.optDouble("z", 0.0);
        this.yaw = (float) hdr.optDouble("yaw", 0.0);
        this.pitch = (float) hdr.optDouble("pitch", 0.0);
        this.iHeight = hdr.optInt("img_height", -1);
        this.iWidth = hdr.optInt("img_width", -1);
        this.iCh = hdr.optInt("img_ch", 3);

        this.modelViewMatrix = toFloat16(hdr.optJSONArray("modelViewMatrix"));
        this.calibrationMatrix = toFloat16(hdr.optJSONArray("projectionMatrix"));

        int remaining = bb.remaining();
        if (remaining < 0) remaining = 0;
        this._pixels = new byte[remaining];
        bb.get(this._pixels);
    }

    private static float[] toFloat16(JSONArray arr) {
        float[] out = new float[16];
        if (arr == null) return out;
        int n = Math.min(16, arr.length());
        for (int i = 0; i < n; i++) out[i] = (float) arr.optDouble(i, 0.0);
        return out;
    }

    public int getWidth() { return iWidth; }
    public int getHeight() { return iHeight; }
    public int getChannels() { return iCh; }

    public int getRGB(int x, int y) {
        if (_pixels == null || iCh < 3 || iWidth <= 0 || iHeight <= 0) return 0;
        int cx = Math.max(0, Math.min(iWidth - 1, x));
        int cy = Math.max(0, Math.min(iHeight - 1, y));
        int off = (cy * iWidth + cx) * iCh;
        int b = _pixels[off] & 0xFF;
        int g = _pixels[off + 1] & 0xFF;
        int r = _pixels[off + 2] & 0xFF;
        return (r << 16) | (g << 8) | b;
    }

    public int getCenterRGB() {
        int cx = Math.max(0, Math.min(iWidth - 1, iWidth / 2));
        int cy = Math.max(0, Math.min(iHeight - 1, iHeight / 2));
        return getRGB(cx, cy);
    }

    public boolean isNonBlack() { return isNonBlack(4096); }
    public boolean isNonBlack(int maxSamples) {
        if (_pixels == null || iCh < 3) return false;
        int stride = iCh;
        int pixels = _pixels.length / stride;
        int step = Math.max(1, pixels / Math.max(1, maxSamples));
        for (int i = 0; i < pixels; i += step) {
            int off = i * stride;
            int b = _pixels[off] & 0xFF;
            int g = _pixels[off + 1] & 0xFF;
            int r = _pixels[off + 2] & 0xFF;
            if ((r | g | b) != 0) return true;
        }
        return false;
    }

    public int countUniqueColors(int maxSamples) {
        if (_pixels == null || iCh < 3) return 0;
        int stride = iCh; int pixels = _pixels.length / stride;
        int step = Math.max(1, pixels / Math.max(1, maxSamples));
        java.util.HashSet<Integer> uniq = new java.util.HashSet<>();
        for (int i = 0; i < pixels; i += step) {
            int off = i * stride;
            int b = _pixels[off] & 0xFF;
            int g = _pixels[off + 1] & 0xFF;
            int r = _pixels[off + 2] & 0xFF;
            int rgb = (r << 16) | (g << 8) | b;
            uniq.add(rgb);
            if (uniq.size() >= maxSamples) break;
        }
        return uniq.size();
    }
}
