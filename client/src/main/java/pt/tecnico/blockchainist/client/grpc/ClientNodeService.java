package pt.tecnico.blockchainist.client.grpc;

import pt.tecnico.blockchainist.contract.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

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
    private static final Metadata.Key<String> HEADER_KEY_DELAY = Metadata.Key.of("delay",
            Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Constructs a new ClientNodeService and establishes a connection to the
     * blockchain node.
     *
     * @param host         the hostname or IP address of the blockchain node
     * @param port         the port number on which the blockchain node is listening
     * @param organization the organization identifier (currently unused)
     */
    public ClientNodeService(String host, int port, String organization) {
        // Open the connection
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();

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
    public CreateWalletResponse createWallet(String userId, String walletId, Integer delay) {
        CreateWalletRequest request = CreateWalletRequest.newBuilder().setUserId(userId).setWalletId(walletId).build();
        return withDelay(blockingStub, delay).createWallet(request);
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
    public DeleteWalletResponse deleteWallet(String userId, String walletId, Integer delay) {
        DeleteWalletRequest request = DeleteWalletRequest.newBuilder().setUserId(userId).setWalletId(walletId).build();
        return withDelay(blockingStub, delay).deleteWallet(request);
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
    public TransferResponse transfer(String srcUserId, String srcWallet, String dstWallet, Long amount, Integer delay) {
        TransferRequest request = TransferRequest.newBuilder().setSrcUserId(srcUserId).setSrcWalletId(srcWallet)
                .setDstWalletId(dstWallet).setValue(amount.longValue()).build();
        return withDelay(blockingStub, delay).transfer(request);
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
        return withDelay(blockingStub, delay).readBalance(request);
    }

    /**
     * Retrieves the current state of the blockchain.
     *
     * @return GetBlockchainStateResponse containing the blockchain state
     *         information
     */
    public GetBlockchainStateResponse getBlockchainState() {
        GetBlockchainStateRequest request = GetBlockchainStateRequest.newBuilder().build();
        return withDelay(blockingStub, null).getBlockchainState(request);
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
    public void createWalletAsync(String userId, String walletId, Integer delay,
            StreamObserver<CreateWalletResponse> observer) {
        CreateWalletRequest request = CreateWalletRequest.newBuilder().setUserId(userId).setWalletId(walletId).build();
        withDelay(asyncStub, delay).createWallet(request, observer);
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
    public void deleteWalletAsync(String userId, String walletId, Integer delay,
            StreamObserver<DeleteWalletResponse> observer) {
        DeleteWalletRequest request = DeleteWalletRequest.newBuilder().setUserId(userId).setWalletId(walletId).build();
        withDelay(asyncStub, delay).deleteWallet(request, observer);
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
            StreamObserver<TransferResponse> observer) {
        TransferRequest request = TransferRequest.newBuilder().setSrcUserId(srcUserId).setSrcWalletId(srcWallet)
                .setDstWalletId(dstWallet).setValue(amount.longValue()).build();
        withDelay(asyncStub, delay).transfer(request, observer);
    }

    /**
     * Helper method to attach the delay metadata header to a gRPC stub.
     *
     * @param <T>   the specific type of the gRPC stub (e.g., blocking or async
     *              stub)
     * @param stub  the base gRPC stub to which the metadata will be attached
     * @param delay the delay value to send; if null, defaults to "0"
     * @return a new stub instance of the same type configured with the metadata
     *         interceptor
     */
    private <T extends io.grpc.stub.AbstractStub<T>> T withDelay(T stub, Integer delay) {
        Metadata metadata = new Metadata();
        metadata.put(HEADER_KEY_DELAY, delay != null ? delay.toString() : "0");
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
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
