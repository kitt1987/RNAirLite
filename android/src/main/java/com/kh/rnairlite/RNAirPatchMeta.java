package com.kh.rnairlite;

import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Created by KH on 26/09/2016.
 */
public class RNAirPatchMeta {

    private static final int PackVersoinSupported = 1;
    private static final int PatchHeaderLength = 64;
    private static final int PachVersionLength = 1;
    private static final int PatchVersionLength = 4;
    private static final int ChecksumLength = 32;

    private ByteBuffer mBytesBuf;
    private final byte[] mBytes;

    public RNAirPatchMeta(byte[] metaBytes) {
        mBytes = metaBytes;
        mBytesBuf = ByteBuffer.wrap(metaBytes);
    }

    public String verify() {
        mBytesBuf.rewind();
        if (mBytesBuf.remaining() != PatchHeaderLength) {
            String error = "The meta file is corrupted which length is " + mBytesBuf.remaining();
            Log.e(RNAirLiteModule.Tag, error);
            return error;
        }

        int packVersion = mBytesBuf.get(0);
        if (packVersion != PackVersoinSupported) {
            String error = "Unsupported pack version " + packVersion;
            Log.e(RNAirLiteModule.Tag, error);
            return error;
        }

        return null;
    }

    public String verifyPatch(ByteBuffer patchBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(mBytes, 0, PachVersionLength + PatchVersionLength);
            byte[] all0s = new byte[PatchHeaderLength - PachVersionLength - PatchVersionLength];
            Arrays.fill(all0s, (byte) 0);
            md.update(all0s);
            md.update(patchBytes);
            byte[] checksum = md.digest();
            byte[] checksumInMeta = new byte[ChecksumLength];
            mBytesBuf.position(PachVersionLength + PatchVersionLength);
            mBytesBuf.get(checksumInMeta);
            if (!Arrays.equals(checksum, checksumInMeta)) {
                String error = "Fail to verify the checksum";
                Log.e(RNAirLiteModule.Tag, error);
                return error;
            }

            return null;
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return e.toString();
        }
    }

    public int getVersion() {
        return mBytesBuf.getInt(PachVersionLength);
    }

    public String save(File dst) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(dst);
            out.write(mBytes);
            out.flush();
            return null;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return e.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return e.toString();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    public static byte[] createVersionBuffer() {
        return new byte[PatchVersionLength];
    }

    public static byte[] createMetaBuffer() {
        return new byte[PatchHeaderLength];
    }

    public static String getVersionByteRange() {
        return PachVersionLength + "-" + PatchVersionLength;
    }
}
