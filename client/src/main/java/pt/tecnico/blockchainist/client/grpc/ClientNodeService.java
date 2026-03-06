package pt.tecnico.blockchainist.client.grpc;

import pt.tecnico.blockchainist.contract.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

/**
 * Service class for client-side gRPC communication with blockchain nodes.
 * This class provides a simplified interface for interacting with the blockchain node,
 * wrapping gRPC calls for wallet and transaction operations.
 */
public class ClientNodeService {
    private final ManagedChannel channel;
    private final NodeServiceGrpc.NodeServiceBlockingStub blockingStub;

    /**
     * Constructs a new ClientNodeService and establishes a connection to the blockchain node.
     *
     * @param host the hostname or IP address of the blockchain node
     * @param port the port number on which the blockchain node is listening
     * @param organization the organization identifier (currently unused)
     */
    public ClientNodeService(String host, int port, String organization) {
        // 1. Open the connection
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();

        // 2. Create the blocking stub
        this.blockingStub = NodeServiceGrpc.newBlockingStub(channel);
    }

    // --- Wrapper Methods for gRPC Calls ---

    /**
     * Creates a new wallet on the blockchain for a specified user.
     *
     * @param userId the unique identifier of the user requesting the wallet
     * @param walletId the unique identifier for the new wallet
     * @param delay optional delay parameter (currently unused)
     * @return CreateWalletResponse containing the result of the wallet creation operation
     */
    public CreateWalletResponse createWallet(String userId, String walletId, Integer delay) {
        CreateWalletRequest request = CreateWalletRequest.newBuilder().setUserId(userId).setWalletId(walletId).build();
        return blockingStub.createWallet(request);
    }

    /**
     * Deletes an existing wallet from the blockchain.
     *
     * @param userId the unique identifier of the user who owns the wallet
     * @param walletId the unique identifier of the wallet to delete
     * @param delay optional delay parameter (currently unused)
     * @return DeleteWalletResponse containing the result of the wallet deletion operation
     */
    public DeleteWalletResponse deleteWallet(String userId, String walletId, Integer delay) {
        DeleteWalletRequest request = DeleteWalletRequest.newBuilder().setUserId(userId).setWalletId(walletId).build();
        return blockingStub.deleteWallet(request);
    }

    /**
     * Transfers cryptocurrency from one wallet to another.
     *
     * @param srcUserId the unique identifier of the user initiating the transfer
     * @param srcWallet the wallet ID from which to transfer funds
     * @param dstWallet the wallet ID to which funds will be transferred
     * @param amount the amount of cryptocurrency to transfer
     * @param delay optional delay parameter (currently unused)
     * @return TransferResponse containing the result of the transfer operation
     */
    public TransferResponse transfer(String srcUserId, String srcWallet, String dstWallet, Long amount, Integer delay) {
        TransferRequest request = TransferRequest.newBuilder().setSrcUserId(srcUserId).setSrcWalletId(srcWallet)
                .setDstWalletId(dstWallet).setValue(amount.longValue()).build();
        return blockingStub.transfer(request);
    }

    /**
     * Reads the current balance of a specified wallet.
     *
     * @param walletId the unique identifier of the wallet to query
     * @param delay optional delay parameter (currently unused)
     * @return ReadBalanceResponse containing the wallet's current balance
     */
    public ReadBalanceResponse readBalance(String walletId, Integer delay) {
        ReadBalanceRequest request = ReadBalanceRequest.newBuilder().setWalletId(walletId).build();
        return blockingStub.readBalance(request);
    }

    /**
     * Retrieves the current state of the blockchain.
     *
     * @return GetBlockchainStateResponse containing the blockchain state information
     */
    public GetBlockchainStateResponse getBlockchainState() {
        GetBlockchainStateRequest request = GetBlockchainStateRequest.newBuilder().build();
        return blockingStub.getBlockchainState(request);
    }

}
