package cc.microblock.TGStickerProvider.hook.TelegramTLParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;


public class SerializedData extends AbstractSerializedData {
    private static final Logger LOGGER = Logger.getLogger(SerializedData.class.getName());
    protected boolean isOut = true;
    private ByteArrayOutputStream outbuf;
    private DataOutputStream out;
    private ByteArrayInputStream inbuf;
    private DataInputStream in;
    private boolean justCalc = false;
    private int len;

    public SerializedData() {
        outbuf = new ByteArrayOutputStream();
        out = new DataOutputStream(outbuf);
    }

    public SerializedData(boolean calculate) {
        if (!calculate) {
            outbuf = new ByteArrayOutputStream();
            out = new DataOutputStream(outbuf);
        }
        justCalc = calculate;
        len = 0;
    }

    public SerializedData(int size) {
        outbuf = new ByteArrayOutputStream(size);
        out = new DataOutputStream(outbuf);
    }

    public SerializedData(byte[] data) {
        isOut = false;
        inbuf = new ByteArrayInputStream(data);
        in = new DataInputStream(inbuf);
        len = 0;
    }

    public void cleanup() {
        try {
            if (inbuf != null) {
                inbuf.close();
                inbuf = null;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "关闭输入缓冲区失败", e);
        }
        try {
            if (in != null) {
                in.close();
                in = null;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "关闭输入流失败", e);
        }
        try {
            if (outbuf != null) {
                outbuf.close();
                outbuf = null;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "关闭输出缓冲区失败", e);
        }
        try {
            if (out != null) {
                out.close();
                out = null;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "关闭输出流失败", e);
        }
    }

    public SerializedData(File file) throws Exception {
        FileInputStream is = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        new DataInputStream(is).readFully(data);
        is.close();

        isOut = false;
        inbuf = new ByteArrayInputStream(data);
        in = new DataInputStream(inbuf);
        len = 0;
    }

    public void writeInt32(int x) {
        if (!justCalc) {
            writeInt32(x, out);
        } else {
            len += 4;
        }
    }

    private void writeInt32(int x, DataOutputStream out) {
        try {
            for (int i = 0; i < 4; i++) {
                out.write(x >> (i * 8));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "写入 int32 失败", e);
        }
    }

    public void writeInt64(long i) {
        if (!justCalc) {
            writeInt64(i, out);
        } else {
            len += 8;
        }
    }

    private void writeInt64(long x, DataOutputStream out) {
        try {
            for (int i = 0; i < 8; i++) {
                out.write((int) (x >> (i * 8)));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "写入 int64 失败", e);
        }
    }

    public void writeBool(boolean value) {
        if (!justCalc) {
            if (value) {
                writeInt32(0x997275b5);
            } else {
                writeInt32(0xbc799737);
            }
        } else {
            len += 4;
        }
    }

    public void writeBytes(byte[] b) {
        try {
            if (!justCalc) {
                out.write(b);
            } else {
                len += b.length;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "写入原始字节数组失败", e);
        }
    }

    public void writeBytes(byte[] b, int offset, int count) {
        try {
            if (!justCalc) {
                out.write(b, offset, count);
            } else {
                len += count;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "写入字节切片失败", e);
        }
    }

    public void writeByte(int i) {
        try {
            if (!justCalc) {
                out.writeByte((byte) i);
            } else {
                len += 1;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "写入单字节失败", e);
        }
    }

    public void writeByte(byte b) {
        try {
            if (!justCalc) {
                out.writeByte(b);
            } else {
                len += 1;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "写入单字节失败", e);
        }
    }

    public void writeByteArray(byte[] b) {
        try {
            if (b.length <= 253) {
                if (!justCalc) {
                    out.write(b.length);
                } else {
                    len += 1;
                }
            } else {
                if (!justCalc) {
                    out.write(254);
                    out.write(b.length);
                    out.write(b.length >> 8);
                    out.write(b.length >> 16);
                } else {
                    len += 4;
                }
            }
            if (!justCalc) {
                out.write(b);
            } else {
                len += b.length;
            }
            int i = b.length <= 253 ? 1 : 4;
            while ((b.length + i) % 4 != 0) {
                if (!justCalc) {
                    out.write(0);
                } else {
                    len += 1;
                }
                i++;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "写入 TL 字节数组失败", e);
        }
    }

    public void writeString(String s) {
        try {
            writeByteArray(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "写入字符串失败", e);
        }
    }

    public void writeByteArray(byte[] b, int offset, int count) {
        try {
            if (count <= 253) {
                if (!justCalc) {
                    out.write(count);
                } else {
                    len += 1;
                }
            } else {
                if (!justCalc) {
                    out.write(254);
                    out.write(count);
                    out.write(count >> 8);
                    out.write(count >> 16);
                } else {
                    len += 4;
                }
            }
            if (!justCalc) {
                out.write(b, offset, count);
            } else {
                len += count;
            }
            int i = count <= 253 ? 1 : 4;
            while ((count + i) % 4 != 0) {
                if (!justCalc) {
                    out.write(0);
                } else {
                    len += 1;
                }
                i++;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "写入 TL 字节数组切片失败", e);
        }
    }

    public void writeDouble(double d) {
        try {
            writeInt64(Double.doubleToRawLongBits(d));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "写入 double 失败", e);
        }
    }

    public void writeFloat(float d) {
        try {
            writeInt32(Float.floatToIntBits(d));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "写入 float 失败", e);
        }
    }

    public int length() {
        if (!justCalc) {
            return isOut ? outbuf.size() : inbuf.available();
        }
        return len;
    }

    protected void set(byte[] newData) {
        isOut = false;
        inbuf = new ByteArrayInputStream(newData);
        in = new DataInputStream(inbuf);
        len = 0;
    }

    public byte[] toByteArray() {
        return outbuf.toByteArray();
    }

    public void skip(int count) {
        if (count <= 0) {
            return;
        }
        if (!justCalc) {
            if (in != null) {
                try {
                    int skipped = in.skipBytes(count);
                    len += skipped;
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "跳过字节失败", e);
                }
            }
        } else {
            len += count;
        }
    }

    public int getPosition() {
        return len;
    }

    // 读取单个无符号字节并在遇到流结束时抛出异常
    private int readUnsignedByteOrThrow() throws Exception {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("unexpected EOF");
        }
        len++;
        return b;
    }

    public boolean readBool(boolean exception) {
        int consructor = readInt32(exception);
        if (consructor == 0x997275b5) {
            return true;
        } else if (consructor == 0xbc799737) {
            return false;
        }
        if (exception) {
            throw new RuntimeException("Not bool value!");
        } else {
            LOGGER.warning("无效的布尔值构造器");
        }
        return false;
    }

    public byte readByte(boolean exception) {
        try {
            return (byte) readUnsignedByteOrThrow();
        } catch (Exception e) {
            if (exception) {
                throw new RuntimeException("read byte error", e);
            } else {
                LOGGER.log(Level.WARNING, "读取单字节失败", e);
            }
        }
        return 0;
    }

    public void readBytes(byte[] b, boolean exception) {
        try {
            if (b.length > remaining()) {
                throw new EOFException("requested " + b.length + " bytes, remaining " + remaining());
            }
            in.readFully(b);
            len += b.length;
        } catch (Exception e) {
            if (exception) {
                throw new RuntimeException("read bytes error", e);
            } else {
                LOGGER.log(Level.WARNING, "读取字节数组失败", e);
            }
        }
    }

    public byte[] readData(int count, boolean exception) {
        if (count < 0 || count > remaining()) {
            if (exception) {
                throw new RuntimeException("invalid readData count: " + count + ", remaining: " + remaining());
            } else {
                LOGGER.warning("非法的 readData 长度: " + count);
                return new byte[0];
            }
        }
        byte[] arr = new byte[count];
        readBytes(arr, exception);
        return arr;
    }

    public String readString(boolean exception) {
        try {
            byte[] b = readTlBytes();
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (exception) {
                throw new RuntimeException("read string error", e);
            } else {
                LOGGER.log(Level.WARNING, "读取字符串失败", e);
            }
        }
        return null;
    }

    public byte[] readByteArray(boolean exception) {
        try {
            return readTlBytes();
        } catch (Exception e) {
            if (exception) {
                throw new RuntimeException("read byte array error", e);
            } else {
                LOGGER.log(Level.WARNING, "读取 TL 字节数组失败", e);
            }
        }
        return null;
    }

    // 按 TL 长度前缀与 4 字节对齐规则读取字节数组，校验剩余长度防止越界分配
    private byte[] readTlBytes() throws Exception {
        int sl = 1;
        int l = readUnsignedByteOrThrow();
        if (l >= 254) {
            int b0 = readUnsignedByteOrThrow();
            int b1 = readUnsignedByteOrThrow();
            int b2 = readUnsignedByteOrThrow();
            l = b0 | (b1 << 8) | (b2 << 16);
            sl = 4;
        }
        int padding = (4 - ((l + sl) % 4)) % 4;
        int rem = remaining();
        if (l < 0 || l > rem - padding) {
            throw new EOFException("invalid TL byte array length " + l + " (padding " + padding + ", remaining " + rem + ")");
        }
        byte[] b = new byte[l];
        in.readFully(b);
        len += l;
        for (int i = 0; i < padding; i++) {
            readUnsignedByteOrThrow();
        }
        return b;
    }

    public double readDouble(boolean exception) {
        try {
            return Double.longBitsToDouble(readInt64(exception));
        } catch (Exception e) {
            if (exception) {
                throw new RuntimeException("read double error", e);
            } else {
                LOGGER.log(Level.WARNING, "读取 double 失败", e);
            }
        }
        return 0;
    }

    public float readFloat(boolean exception) {
        try {
            return Float.intBitsToFloat(readInt32(exception));
        } catch (Exception e) {
            if (exception) {
                throw new RuntimeException("read float error", e);
            } else {
                LOGGER.log(Level.WARNING, "读取 float 失败", e);
            }
        }
        return 0;
    }

    public int readInt32(boolean exception) {
        try {
            int i = 0;
            for (int j = 0; j < 4; j++) {
                i |= (readUnsignedByteOrThrow() << (j * 8));
            }
            return i;
        } catch (Exception e) {
            if (exception) {
                throw new RuntimeException("read int32 error", e);
            } else {
                LOGGER.log(Level.WARNING, "读取 int32 失败", e);
            }
        }
        return 0;
    }

    public long readInt64(boolean exception) {
        try {
            long i = 0;
            for (int j = 0; j < 8; j++) {
                i |= ((long) readUnsignedByteOrThrow() << (j * 8));
            }
            return i;
        } catch (Exception e) {
            if (exception) {
                throw new RuntimeException("read int64 error", e);
            } else {
                LOGGER.log(Level.WARNING, "读取 int64 失败", e);
            }
        }
        return 0;
    }

    @Override
    public int remaining() {
        try {
            return in != null ? in.available() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}