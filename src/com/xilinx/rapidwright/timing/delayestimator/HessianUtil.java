package com.xilinx.rapidwright.timing.delayestimator;

import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.SerializerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class HessianUtil {

    // -------------------------   (de)serialize -----------------------------

    private static final SerializerFactory serializerFactory = new SerializerFactory();

    public static <T> T deserialize(byte[] array) throws IOException {
        Object obj = null;
        ByteArrayInputStream bais = new ByteArrayInputStream(array);
        Hessian2Input hi = new Hessian2Input(bais);
        hi.setSerializerFactory(serializerFactory);
        hi.setCloseStreamOnClose(true);
        hi.startMessage();
        obj = hi.readObject();
        hi.completeMessage();

        hi.close();
        return (T) obj;
    }

    public static byte[] serialize(Object data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Hessian2Output ho = new Hessian2Output(baos);
        ho.setSerializerFactory(serializerFactory);
        ho.setCloseStreamOnClose(true);
        ho.startMessage();
        ho.writeObject(data);
        ho.completeMessage();

        ho.close();
        return baos.toByteArray();
    }


    // -------------------------   read/wight byte[] from/to file  -----------------------------

    // Method which write the bytes into a file
    static void writeByte(byte[] bytes, String fileName) {
        try {
            OutputStream os = new FileOutputStream(fileName);
            os.write(bytes);
            os.close();
        } catch (IOException e) {
            System.out.println("Exception: " + e);
        }
    }

    static byte[] readByte(String fileName) {
        Path path = Paths.get(fileName);
        byte[] data = null;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            System.out.println("Exception: " + e);
        }
        return data;
    }
}