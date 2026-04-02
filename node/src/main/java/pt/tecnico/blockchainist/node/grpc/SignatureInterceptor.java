package pt.tecnico.blockchainist.node.grpc;

import io.grpc.*;
import io.grpc.ClientCall.Listener;

import com.google.protobuf.MessageLite;
import pt.tecnico.blockchainist.common.Constants;
import pt.tecnico.blockchainist.common.Utils;

import java.security.PublicKey;

public class SignatureInterceptor implements ClientInterceptor {

    private final PublicKey publicKey;

    public SignatureInterceptor() {
        try {
            this.publicKey = (PublicKey) Utils.loadKey("seq/Seq.pub", false);
        } catch (Exception e) {
            System.err.println("[ERROR]: Public Key failed loading - " + e.getMessage());
            throw new RuntimeException("Public Key failed loading");
        }
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        // Ignore other calls
        if (!method.getFullMethodName().contains("DeliverBlock")) {
            return next.newCall(method, callOptions);
        }

        // Override normal behaviour
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                super.start(new SignatureVerificationListener<>(responseListener), headers);
            }
        };
    }

    private class SignatureVerificationListener<RespT>
            extends ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT> {

        private RespT capturedResponse = null;

        public SignatureVerificationListener(Listener<RespT> delegate) {
            super(delegate);
        }

        @Override
        public void onMessage(RespT message) {
            this.capturedResponse = message;
        }

        @Override
        public void onClose(Status status, Metadata trailers) {
            if (status.isOk() && capturedResponse != null) {
                byte[] signatureBytes = trailers.get(Constants.KEY_SIGNATURE);
                Status validationStatus = Utils.verifySignature((MessageLite) capturedResponse, signatureBytes,
                        publicKey);
                if (!validationStatus.isOk()) {
                    status = validationStatus;
                } else {
                    super.onMessage(capturedResponse);
                }
            }
            // Pass the final status down the pipeline
            super.onClose(status, trailers);
        }
    }
}
