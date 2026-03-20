package pt.tecnico.blockchainist.node;

import io.grpc.stub.StreamObserver;
import pt.tecnico.blockchainist.contract.*;
import pt.tecnico.blockchainist.node.domain.NodeState;
import pt.tecnico.blockchainist.common.Transaction;
import pt.tecnico.blockchainist.common.Utils;
import pt.tecnico.blockchainist.node.grpc.DelayInterceptor;
import pt.tecnico.blockchainist.node.grpc.NodeSequencerService;

import java.util.List;
import io.grpc.Status;

/**
 * Implementation of the node gRPC service.
 * This class handles client requests for blockchain operations by coordinating
 * with the sequencer
 * and managing the local blockchain state.
 */
public class NodeServiceImpl extends NodeServiceGrpc.NodeServiceImplBase {

    private final NodeState nodeState = new NodeState();
    private final NodeSequencerService sequencer;
    private volatile boolean syncing = true;
    private long syncingTarget = 0;

    /**
     * Constructs a new NodeServiceImpl and starts the background transaction
     * fetcher.
     *
     * @param organization the organization identifier for this node
     * @param sequencer    the service for communicating with the sequencer
     */
    public NodeServiceImpl(String organization, NodeSequencerService sequencer) {
        this.sequencer = sequencer;

        // Heartbeat
        startFetchingTransactions();
    }

    /**
     * Handles wallet creation requests from clients.
     * Sends the transaction to the sequencer and waits for it to be processed.
     *
     * @param request          the create wallet request containing user and wallet
     *                         identifiers
     * @param responseObserver the observer to send the response back to the client
     */
    @Override
    public void createWallet(CreateWalletRequest request, StreamObserver<CreateWalletResponse> responseObserver) {
        try {
            // Log
            Utils.debug("Received CreateWallet request: userId=%s, walletId=%s",
                    request.getUserId(), request.getWalletId());

            // Reject if syncing with sequencer
            ensureReady();

            // Check
            if (request.getTransactionId().isBlank())
                throw new IllegalArgumentException("Transaction ID can't be blank");
            if (request.getUserId().isBlank())
                throw new IllegalArgumentException("User ID can't be blank");
            if (request.getWalletId().isBlank())
                throw new IllegalArgumentException("Wallet ID can't be blank");

            // Map
            Transaction tx = new Transaction(
                    Transaction.Type.CREATE,
                    request.getUserId(),
                    "",
                    request.getWalletId(),
                    0,
                    request.getTransactionId());
            pt.tecnico.blockchainist.contract.Transaction protoTx = Utils.convertToProto(tx);

            // Delay
            delayIfRequested();

            // Request Sequencer
            Utils.debug("Sending transaction to sequencer: CREATE userId=%s walletId=%s",
                    request.getUserId(), request.getWalletId());
            BroadcastResponse seqRes = sequencer.broadcast(protoTx);
            int assignedSequence = seqRes.getSequenceNumber();

            // Wait
            nodeState.waitForSequence(assignedSequence, request.getTransactionId());

            // Reply
            responseObserver.onNext(CreateWalletResponse.getDefaultInstance());
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            // Error Handling
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            responseObserver.onError(Status.INTERNAL.withDescription("Request interrupted").asRuntimeException());
        } catch (IllegalStateException e) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /**
     * Handles wallet deletion requests from clients.
     * Sends the transaction to the sequencer and waits for it to be processed.
     *
     * @param request          the delete wallet request containing user and wallet
     *                         identifiers
     * @param responseObserver the observer to send the response back to the client
     */
    @Override
    public void deleteWallet(DeleteWalletRequest request, StreamObserver<DeleteWalletResponse> responseObserver) {
        try {
            // Log
            Utils.debug("Received DeleteWallet request: userId=%s, walletId=%s",
                    request.getUserId(), request.getWalletId());

            // Reject if syncing with sequencer
            ensureReady();

            // Check
            if (request.getTransactionId().isBlank())
                throw new IllegalArgumentException("Transaction ID can't be blank");
            if (request.getUserId().isBlank())
                throw new IllegalArgumentException("User ID can't be blank");
            if (request.getWalletId().isBlank())
                throw new IllegalArgumentException("Wallet ID can't be blank");

            // Map
            Transaction tx = new Transaction(
                    Transaction.Type.DELETE,
                    request.getUserId(),
                    request.getWalletId(),
                    "",
                    0,
                    request.getTransactionId());
            pt.tecnico.blockchainist.contract.Transaction protoTx = Utils.convertToProto(tx);

            // Delay
            delayIfRequested();

            // Request Sequencer
            Utils.debug("Sending transaction to sequencer: DELETE userId=%s walletId=%s",
                    request.getUserId(), request.getWalletId());
            BroadcastResponse seqRes = sequencer.broadcast(protoTx);
            int assignedSequence = seqRes.getSequenceNumber();

            // Wait
            nodeState.waitForSequence(assignedSequence, request.getTransactionId());

            // Reply
            responseObserver.onNext(DeleteWalletResponse.getDefaultInstance());
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            // Error Handling
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            responseObserver.onError(Status.INTERNAL.withDescription("Request interrupted").asRuntimeException());
        } catch (IllegalStateException e) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /**
     * Handles transfer requests from clients.
     * Sends the transaction to the sequencer and waits for it to be processed.
     *
     * @param request          the transfer request containing source, destination,
     *                         and amount information
     * @param responseObserver the observer to send the response back to the client
     */
    @Override
    public void transfer(TransferRequest request, StreamObserver<TransferResponse> responseObserver) {
        try {
            // Log
            Utils.debug("Received Transfer request: userId=%s, srcWalletId=%s, dstWalletId=%s, amount=%d",
                    request.getSrcUserId(), request.getSrcWalletId(), request.getDstWalletId(), request.getValue());

            // Reject if syncing with sequencer
            ensureReady();

            // Check
            if (request.getTransactionId().isBlank())
                throw new IllegalArgumentException("Transaction ID can't be blank");
            if (request.getSrcUserId().isBlank())
                throw new IllegalArgumentException("Source User ID can't be blank");
            if (request.getSrcWalletId().isBlank())
                throw new IllegalArgumentException("Source Wallet ID can't be blank");
            if (request.getDstWalletId().isBlank())
                throw new IllegalArgumentException("Destination Wallet ID can't be blank");
            if (request.getValue() <= 0)
                throw new IllegalArgumentException("Transfer Amount must be positive");

            // Map
            Transaction tx = new Transaction(
                    Transaction.Type.TRANSFER,
                    request.getSrcUserId(),
                    request.getSrcWalletId(),
                    request.getDstWalletId(),
                    request.getValue(),
                    request.getTransactionId());
            pt.tecnico.blockchainist.contract.Transaction protoTx = Utils.convertToProto(tx);

            // Delay
            delayIfRequested();

            // Request Sequencer
            Utils.debug(
                    "Sending transaction to sequencer: TRANSFER userId=%s, srcWalletId=%s, dstWalletId=%s, amount=%d",
                    request.getSrcUserId(), request.getSrcWalletId(), request.getDstWalletId(), request.getValue());
            BroadcastResponse seqRes = sequencer.broadcast(protoTx);
            int assignedSequence = seqRes.getSequenceNumber();

            // Wait
            nodeState.waitForSequence(assignedSequence, request.getTransactionId());

            // Reply
            responseObserver.onNext(TransferResponse.getDefaultInstance());
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            // Error Handling
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            responseObserver.onError(Status.INTERNAL.withDescription("Request interrupted").asRuntimeException());
        } catch (IllegalStateException e) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /**
     * Handles read balance requests from clients.
     * This is a read-only operation that queries the current node state.
     *
     * @param request          the read balance request containing the wallet
     *                         identifier
     * @param responseObserver the observer to send the balance response back to the
     *                         client
     */
    @Override
    public void readBalance(ReadBalanceRequest request, StreamObserver<ReadBalanceResponse> responseObserver) {
        try {
            // Log
            Utils.debug("Received ReadBalance request: walletId=%s", request.getWalletId());

            // Reject if syncing with sequencer
            ensureReady();

            // Check
            if (request.getWalletId().isBlank())
                throw new IllegalArgumentException("Wallet ID can't be blank");

            // Delay
            delayIfRequested();

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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            responseObserver.onError(Status.INTERNAL.withDescription("Request interrupted").asRuntimeException());
        } catch (IllegalStateException e) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /**
     * Handles blockchain state requests from clients.
     * Returns the complete transaction history stored in the node.
     *
     * @param request          the blockchain state request
     * @param responseObserver the observer to send the blockchain state back to the
     *                         client
     */
    @Override
    public void getBlockchainState(GetBlockchainStateRequest request,
            StreamObserver<GetBlockchainStateResponse> responseObserver) {
        try {
            // Log
            Utils.debug("Received BlockchainState request");

            // Reject if syncing with sequencer
            ensureReady();

            // Delay
            delayIfRequested();

            // Execute
            List<Transaction> transactions = nodeState.getBlockchainState();

            // Build Reply
            GetBlockchainStateResponse.Builder builder = GetBlockchainStateResponse.newBuilder();
            for (Transaction tx : transactions) {
                builder.addTransactions(Utils.convertToProto(tx));
            }
            GetBlockchainStateResponse response = builder.build();

            // Reply
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            responseObserver.onError(Status.INTERNAL.withDescription("Request interrupted").asRuntimeException());
        } catch (IllegalStateException e) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /**
     * Utility method to introduce an artificial delay in request processing if
     * specified in the gRPC metadata headers.
     *
     * @throws InterruptedException if the thread is interrupted while sleeping
     */
    private void delayIfRequested() throws InterruptedException {
        Integer delay = DelayInterceptor.DELAY_CONTEXT_KEY.get();
        if (delay != null && delay > 0) {
            Utils.debug("Delaying request by %d ms as per metadata header", delay);
            Thread.sleep(delay * 1000L);
        }
    }

    /**
     * Ensures that the node is ready to process requests by checking if it is still
     * syncing with the sequencer.
     */
    private void ensureReady() {
        if (syncing) {
            throw new IllegalStateException("Node is syncing with sequencer. Please try again later.");
        }
    }

    /**
     * Starts a background daemon thread that continuously fetches transactions from
     * the sequencer and applies them to the local blockchain state.
     */
    private void startFetchingTransactions() {
        Thread fetcher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int next = nodeState.getNextBlockNumber();

                    // Reject transactions until synced with sequencer
                    if (syncing && next >= syncingTarget) {
                        int sequencerCurrent = sequencer.getLastBlockNumber().getBlockNumber();
                        if (next > sequencerCurrent) {
                            Utils.debug("Node is synced with sequencer (next=%d, sequencerCurrent=%d). Waiting...",
                                    next, sequencerCurrent);
                            syncing = false;
                        } else {
                            syncingTarget = sequencerCurrent + 1;
                        }
                    }

                    // Request Sequencer
                    DeliverBlockResponse response = sequencer.deliverBlock(next);
                    List<pt.tecnico.blockchainist.contract.Transaction> block = response.getTransactionList();

                    // Execute
                    if (!block.isEmpty()) {
                        Utils.debug("Received Block %d with %d transactions", next, block.size());
                        List<Transaction> domainBlock = block.stream().map(Utils::convertToDomain).toList();
                        nodeState.applyBlock(domainBlock);
                    } else {
                        Thread.sleep(100);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("[Error] [Heartbeat] " + e.getMessage());
                    try {
                        // Wait a bit before retrying
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            System.out.println("[Heartbeat] Interrupted. Closing...");
        });
        fetcher.setDaemon(true);
        fetcher.start();
    }

}
