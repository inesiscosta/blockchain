package pt.tecnico.blockchainist.common;

import java.util.concurrent.atomic.AtomicReference;
import io.grpc.Context;
import io.grpc.Metadata;

public class Constants {
        public static final Metadata.Key<byte[]> KEY_SIGNATURE = Metadata.Key.of("signature-bin",
                        Metadata.BINARY_BYTE_MARSHALLER);
        public static final Metadata.Key<String> HEADER_KEY_DELAY = Metadata.Key.of("delay",
                        Metadata.ASCII_STRING_MARSHALLER);

        public static final Context.Key<AtomicReference<byte[]>> SIGNATURE_REF_KEY = Context.key("signature-ref");
}
