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

    private final int PackVersoinSupported = 1;
    private final int PatchHeaderLength = 64;
    private final int PachVersionLength = 1;
    private final int PatchVersionLength = 4;
    private final int ChecksumLength = 32;

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

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public String verifyPatch(byte[] patchBytes) {
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
            Log.d(RNAirLiteModule.Tag, bytesToHex(checksum));
            Log.d(RNAirLiteModule.Tag, bytesToHex(checksumInMeta));
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
}
