package pt.tecnico.blockchainist.client.grpc;

import pt.tecnico.blockchainist.contract.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class ClientNodeService {
    private final ManagedChannel channel;
    private final NodeServiceGrpc.NodeServiceBlockingStub blockingStub;

    public ClientNodeService(String host, int port, String organization) {
        // 1. Open the connection
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();

        // 2. Create the blocking stub
        this.blockingStub = NodeServiceGrpc.newBlockingStub(channel);
    }

    // --- Wrapper Methods for gRPC Calls ---

    public CreateWalletResponse createWallet(String userId, String walletId, Integer delay) {
        CreateWalletRequest request = CreateWalletRequest.newBuilder().setUserId(userId).setWalletId(walletId).build();
        return blockingStub.createWallet(request);
    }

    public DeleteWalletResponse deleteWallet(String userId, String walletId, Integer delay) {
        DeleteWalletRequest request = DeleteWalletRequest.newBuilder().setUserId(userId).setWalletId(walletId).build();
        return blockingStub.deleteWallet(request);
    }

    public TransferResponse transfer(String srcUserId, String srcWallet, String dstWallet, Long amount, Integer delay) {
        TransferRequest request = TransferRequest.newBuilder().setSrcUserId(srcUserId).setSrcWalletId(srcWallet)
                .setDstWalletId(dstWallet).setValue(amount.longValue()).build();
        return blockingStub.transfer(request);
    }

    public ReadBalanceResponse readBalance(String walletId, Integer delay) {
        ReadBalanceRequest request = ReadBalanceRequest.newBuilder().setWalletId(walletId).build();
        return blockingStub.readBalance(request);
    }

    public GetBlockchainStateResponse getBlockchainState() {
        GetBlockchainStateRequest request = GetBlockchainStateRequest.newBuilder().build();
        return blockingStub.getBlockchainState(request);
    }

}
