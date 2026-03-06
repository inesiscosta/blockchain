package pt.tecnico.blockchainist.node;

import io.grpc.stub.StreamObserver;
import pt.tecnico.blockchainist.contract.*;
import pt.tecnico.blockchainist.node.domain.NodeState;
import pt.tecnico.blockchainist.node.domain.Transaction;

import java.util.List;

import io.grpc.Status;

public class NodeServiceImpl extends NodeServiceGrpc.NodeServiceImplBase {

    private final NodeState nodeState = new NodeState();
    @SuppressWarnings("unused")
	private final String organization;
    @SuppressWarnings("unused")
	private final String sequencerAddress;

    public NodeServiceImpl(String organization, String sequencerAddress) {
        this.organization = organization;
        this.sequencerAddress = sequencerAddress;
    }

    @Override
    public void createWallet(CreateWalletRequest request, StreamObserver<CreateWalletResponse> responseObserver) {
        try {
            // Map
            Transaction tx = new Transaction(
                    Transaction.Type.CREATE,
                    request.getUserId(),
                    "",
                    request.getWalletId(),
                    0);

            // Execute
            nodeState.applyTransaction(tx);

            // Reply
            responseObserver.onNext(CreateWalletResponse.getDefaultInstance());
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            // Error Handling
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void deleteWallet(DeleteWalletRequest request, StreamObserver<DeleteWalletResponse> responseObserver) {
        try {
            // Map
            Transaction tx = new Transaction(
                    Transaction.Type.DELETE,
                    request.getUserId(),
                    request.getWalletId(),
                    "",
                    0);

            // Execute
            nodeState.applyTransaction(tx);

            // Reply
            responseObserver.onNext(DeleteWalletResponse.getDefaultInstance());
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            // Error Handling
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void transfer(TransferRequest request, StreamObserver<TransferResponse> responseObserver) {
        try {
            // Map
            Transaction tx = new Transaction(
                    Transaction.Type.TRANSFER,
                    request.getSrcUserId(),
                    request.getSrcWalletId(),
                    request.getDstWalletId(),
                    request.getValue());

            // Execute
            nodeState.applyTransaction(tx);

            // Reply
            responseObserver.onNext(TransferResponse.getDefaultInstance());
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            // Error Handling
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void readBalance(ReadBalanceRequest request, StreamObserver<ReadBalanceResponse> responseObserver) {
        try {
            // Execute
            long balance = nodeState.readBalance(request.getWalletId());

            // Build Reply
            ReadBalanceResponse resp = ReadBalanceResponse.newBuilder()
                    .setBalance(balance)
                    .build();

            // Reply
            responseObserver.onNext(resp);
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            // Error Handling
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getBlockchainState(GetBlockchainStateRequest request,
            StreamObserver<GetBlockchainStateResponse> responseObserver) {
        // Execute
        List<Transaction> transactions = nodeState.getBlockchainState();

        // Build Reply
        GetBlockchainStateResponse.Builder builder = GetBlockchainStateResponse.newBuilder();
        for (Transaction tx : transactions) {
            builder.addTransactions(convertToProto(tx));
        }
        GetBlockchainStateResponse response = builder.build();

        // Reply
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private pt.tecnico.blockchainist.contract.Transaction convertToProto(Transaction domainTx) {
        var builder = pt.tecnico.blockchainist.contract.Transaction.newBuilder();

        switch (domainTx.type()) {
            case CREATE -> builder.setCreateWallet(CreateWalletRequest.newBuilder()
                    .setUserId(domainTx.srcUserId())
                    .setWalletId(domainTx.dstWalletId())
                    .build());

            case DELETE -> builder.setDeleteWallet(DeleteWalletRequest.newBuilder()
                    .setUserId(domainTx.srcUserId())
                    .setWalletId(domainTx.srcWalletId())
                    .build());

            case TRANSFER -> builder.setTransfer(TransferRequest.newBuilder()
                    .setSrcUserId(domainTx.srcUserId())
                    .setSrcWalletId(domainTx.srcWalletId())
                    .setDstWalletId(domainTx.dstWalletId())
                    .setValue(domainTx.amount())
                    .build());
        }
        return builder.build();
    }

}
