package pt.tecnico.blockchainist.client.grpc;

import pt.tecnico.blockchainist.common.Constants;
import pt.tecnico.blockchainist.common.Utils;
import pt.tecnico.blockchainist.contract.*;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.TimeUnit;
import com.google.protobuf.MessageLite;
import java.security.PrivateKey;

/**
 * Service class for client-side gRPC communication with blockchain nodes.
 * This class provides a simplified interface for interacting with the
 * blockchain node,
 * wrapping gRPC calls for wallet and transaction operations.
 */
public class ClientNodeService {
    private final ManagedChannel channel;
    private final NodeServiceGrpc.NodeServiceBlockingStub blockingStub;
    private final NodeServiceGrpc.NodeServiceStub asyncStub;
    private final int timeout;
    private final String organization;

    /**
     * Constructs a new ClientNodeService and establishes a connection to the
     * blockchain node.
     *
     * @param host         the hostname or IP address of the blockchain node
     * @param port         the port number on which the blockchain node is listening
     * @param organization the organization identifier (currently unused)
     */
    public ClientNodeService(String host, int port, String organization, int timeout) {
        // Open the connection
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.timeout = timeout;
        this.organization = organization;

        // Create the stubs
        this.blockingStub = NodeServiceGrpc.newBlockingStub(channel);
        this.asyncStub = NodeServiceGrpc.newStub(channel);
    }

    // --- Wrapper Methods for gRPC Calls ---

    /**
     * Creates a new wallet on the blockchain for a specified user.
     *
     * @param userId   the unique identifier of the user requesting the wallet
     * @param walletId the unique identifier for the new wallet
     * @param delay    optional delay parameter (currently unused)
     * @return CreateWalletResponse containing the result of the wallet creation
     *         operation
     */
    public CreateWalletResponse createWallet(String userId, String walletId, Integer delay, String transactionId) {
        CreateWalletRequest request = CreateWalletRequest.newBuilder().setUserId(userId).setWalletId(walletId)
                .setTransactionId(transactionId).setOrganization(organization).build();
        byte[] signature = generateSignature(userId, request, "CreateWalletRequest");
        return withMetadata(blockingStub, delay, signature).createWallet(request);
    }

    /**
     * Deletes an existing wallet from the blockchain.
     *
     * @param userId   the unique identifier of the user who owns the wallet
     * @param walletId the unique identifier of the wallet to delete
     * @param delay    optional delay parameter (currently unused)
     * @return DeleteWalletResponse containing the result of the wallet deletion
     *         operation
     */
    public DeleteWalletResponse deleteWallet(String userId, String walletId, Integer delay, String transactionId) {
        DeleteWalletRequest request = DeleteWalletRequest.newBuilder()
                .setUserId(userId)
                .setWalletId(walletId)
                .setTransactionId(transactionId)
                .build();
        byte[] signature = generateSignature(userId, request, "DeleteWalletRequest");
        return withMetadata(blockingStub, delay, signature).deleteWallet(request);
    }

    /**
     * Transfers cryptocurrency from one wallet to another.
     *
     * @param srcUserId the unique identifier of the user initiating the transfer
     * @param srcWallet the wallet ID from which to transfer funds
     * @param dstWallet the wallet ID to which funds will be transferred
     * @param amount    the amount of cryptocurrency to transfer
     * @param delay     optional delay parameter (currently unused)
     * @return TransferResponse containing the result of the transfer operation
     */
    public TransferResponse transfer(String srcUserId, String srcWallet, String dstWallet, Long amount, Integer delay,
            String transactionId) {
        TransferRequest request = TransferRequest.newBuilder()
                .setSrcUserId(srcUserId)
                .setSrcWalletId(srcWallet)
                .setDstWalletId(dstWallet)
                .setValue(amount.longValue())
                .setTransactionId(transactionId)
                .build();
        byte[] signature = generateSignature(srcUserId, request, "TransferRequest");
        return withMetadata(blockingStub, delay, signature).transfer(request);
    }

    /**
     * Reads the current balance of a specified wallet.
     *
     * @param walletId the unique identifier of the wallet to query
     * @param delay    optional delay parameter (currently unused)
     * @return ReadBalanceResponse containing the wallet's current balance
     */
    public ReadBalanceResponse readBalance(String walletId, Integer delay) {
        ReadBalanceRequest request = ReadBalanceRequest.newBuilder().setWalletId(walletId).build();
        return withMetadata(blockingStub, delay, null).readBalance(request);
    }

    /**
     * Retrieves the current state of the blockchain.
     *
     * @return GetBlockchainStateResponse containing the blockchain state
     *         information
     */
    public GetBlockchainStateResponse getBlockchainState() {
        GetBlockchainStateRequest request = GetBlockchainStateRequest.newBuilder().build();
        return withMetadata(blockingStub, null, null).getBlockchainState(request);
    }

    /**
     * Creates a new wallet on the blockchain for a specified user.
     *
     * @param userId   the unique identifier of the user requesting the wallet
     * @param walletId the unique identifier for the new wallet
     * @param delay    optional delay parameter (currently unused)
     * @param observer the StreamObserver to receive the asynchronous response
     * @return CreateWalletResponse containing the result of the wallet creation
     *         operation
     */
    public void createWalletAsync(String userId, String walletId, Integer delay, String transactionId,
            StreamObserver<CreateWalletResponse> observer) {
        CreateWalletRequest request = CreateWalletRequest.newBuilder()
                .setUserId(userId)
                .setWalletId(walletId)
                .setTransactionId(transactionId)
                .setOrganization(organization)
                .build();
        byte[] signature = generateSignature(userId, request, "CreateWalletRequest", observer);
        if (signature == null)
            return;
        withMetadata(asyncStub, delay, signature).createWallet(request, observer);
    }

    /**
     * Deletes an existing wallet from the blockchain.
     *
     * @param userId   the unique identifier of the user who owns the wallet
     * @param walletId the unique identifier of the wallet to delete
     * @param delay    optional delay parameter (currently unused)
     * @param observer the StreamObserver to receive the asynchronous response
     * @return DeleteWalletResponse containing the result of the wallet deletion
     *         operation
     */
    public void deleteWalletAsync(String userId, String walletId, Integer delay, String transactionId,
            StreamObserver<DeleteWalletResponse> observer) {
        DeleteWalletRequest request = DeleteWalletRequest.newBuilder()
                .setUserId(userId)
                .setWalletId(walletId)
                .setTransactionId(transactionId)
                .build();
        byte[] signature = generateSignature(userId, request, "DeleteWalletRequest", observer);
        if (signature == null)
            return;
        withMetadata(asyncStub, delay, signature).deleteWallet(request, observer);
    }

    /**
     * Transfers cryptocurrency from one wallet to another.
     *
     * @param srcUserId the unique identifier of the user initiating the transfer
     * @param srcWallet the wallet ID from which to transfer funds
     * @param dstWallet the wallet ID to which funds will be transferred
     * @param amount    the amount of cryptocurrency to transfer
     * @param delay     optional delay parameter (currently unused)
     * @param observer  the StreamObserver to receive the asynchronous response
     * @return TransferResponse containing the result of the transfer operation
     */
    public void transferAsync(String srcUserId, String srcWallet, String dstWallet, Long amount, Integer delay,
            String transactionId,
            StreamObserver<TransferResponse> observer) {
        TransferRequest request = TransferRequest.newBuilder()
                .setSrcUserId(srcUserId)
                .setSrcWalletId(srcWallet)
                .setDstWalletId(dstWallet)
                .setValue(amount.longValue())
                .setTransactionId(transactionId)
                .build();
        byte[] signature = generateSignature(srcUserId, request, "TransferRequest", observer);
        if (signature == null)
            return;
        withMetadata(asyncStub, delay, signature).transfer(request, observer);
    }

    private <T extends io.grpc.stub.AbstractStub<T>> T withMetadata(T stub, Integer delay, byte[] signature) {
        Metadata metadata = new Metadata();
        metadata.put(Constants.HEADER_KEY_DELAY, delay != null ? delay.toString() : "0");
        if (signature != null) {
            metadata.put(Constants.KEY_SIGNATURE, signature);
        }
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .withDeadlineAfter(timeout, TimeUnit.SECONDS);
    }

    private byte[] generateSignature(String userId, MessageLite request, String operationName) {
        try {
            PrivateKey privKey = (PrivateKey) Utils.loadKey(userId + ".priv", true);
            return Utils.signBytes(request, privKey);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to sign " + operationName + ": " + e.getMessage(), e);
        }
    }

    private byte[] generateSignature(String userId, MessageLite request, String operationName,
            io.grpc.stub.StreamObserver<?> observer) {
        try {
            return generateSignature(userId, request, operationName);
        } catch (IllegalArgumentException e) {
            observer.onError(e);
            return null;
        }
    }

    /**
     * Shuts down the gRPC channel connection to the sequencer.
     */
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
        }
    }
}
