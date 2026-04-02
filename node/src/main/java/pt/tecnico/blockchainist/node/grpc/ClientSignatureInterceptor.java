package pt.tecnico.blockchainist.node.grpc;

import com.google.protobuf.MessageLite;
import io.grpc.*;
import pt.tecnico.blockchainist.common.Constants;
import pt.tecnico.blockchainist.common.Utils;
import pt.tecnico.blockchainist.contract.CreateWalletRequest;
import pt.tecnico.blockchainist.contract.DeleteWalletRequest;
import pt.tecnico.blockchainist.contract.TransferRequest;

import java.security.PublicKey;

public class ClientSignatureInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String method = call.getMethodDescriptor().getFullMethodName();

        // Skip methods without signature
        if (method.contains("ReadBalance") || method.contains("GetBlockchainState")) {
            return next.startCall(call, headers);
        }

        // Check if signature exists
        byte[] signature = headers.get(Constants.KEY_SIGNATURE);
        if (signature == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing signature in headers"), new Metadata());
            return new ServerCall.Listener<ReqT>() {
            };
        }

        // Override normal behaviour
        return new ClientSignatureListener<>(next.startCall(call, headers), signature, call);
    }

    private class ClientSignatureListener<ReqT, RespT>
            extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

        private final byte[] signature;
        private final ServerCall<ReqT, RespT> call;
        private boolean isClosed = false;

        public ClientSignatureListener(ServerCall.Listener<ReqT> delegate, byte[] signature,
                ServerCall<ReqT, RespT> call) {
            super(delegate);
            this.signature = signature;
            this.call = call;
        }

        @Override
        public void onMessage(ReqT message) {
            try {
                String userId = extractUserId(message);
                if (userId == null) {
                    isClosed = true;
                    call.close(Status.INTERNAL.withDescription("Failed to extract userId from message"),
                            new Metadata());
                    return;
                }

                PublicKey pubKey = (PublicKey) Utils.loadKey(userId + ".pub", false);
                Status validationStatus = Utils.verifySignature((MessageLite) message, signature, pubKey);

                if (!validationStatus.isOk()) {
                    isClosed = true;
                    call.close(validationStatus, new Metadata());
                    return;
                }

                // Proceed if valid
                super.onMessage(message);

            } catch (IllegalArgumentException e) {
                isClosed = true;
                call.close(
                        Status.UNAUTHENTICATED.withDescription("Public key not found for user: " + e.getMessage()),
                        new Metadata());
            } catch (Exception e) {
                isClosed = true;
                call.close(Status.INTERNAL.withDescription("Signature validation error: " + e.getMessage()),
                        new Metadata());
            }
        }

        @Override
        public void onHalfClose() {
            if (!isClosed) {
                super.onHalfClose();
            }
        }
    }

    private String extractUserId(Object message) {
        if (message instanceof CreateWalletRequest req) {
            return req.getUserId();
        } else if (message instanceof DeleteWalletRequest req) {
            return req.getUserId();
        } else if (message instanceof TransferRequest req) {
            return req.getSrcUserId();
        }
        return null;
    }
}
