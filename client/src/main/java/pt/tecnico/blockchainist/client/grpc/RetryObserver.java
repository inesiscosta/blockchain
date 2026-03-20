package pt.tecnico.blockchainist.client.grpc;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.function.BiConsumer;
import io.grpc.Status.Code;

/**
 * An observer that extends MessageObserver to add retry capabilities for asynchronous gRPC calls.
 * If a call fails with UNAVAILABLE or DEADLINE_EXCEEDED, it automatically retries the call on the next available node.
 * 
 * @param <T> the type of the gRPC response message
 */
public class RetryObserver<T> extends MessageObserver<T> {

    private final long commandNumber;
    private final List<ClientNodeService> nodes;
    private final BiConsumer<ClientNodeService, StreamObserver<T>> grpcCall;
    private final int maxAttempts;

    private int attempts = 0;
    private int currentNodeIndex;

    /**
     * Constructs a new RetryObserver.
     *
     * @param commandNumber    the sequential number of this command, used for logging
     * @param nodes            the list of available blockchain nodes
     * @param initialNodeIndex the index of the node that the call is initially directed to
     * @param grpcCall         the lambda defining the gRPC call to be retried
     */
    public RetryObserver(long commandNumber, List<ClientNodeService> nodes, int initialNodeIndex,
                         BiConsumer<ClientNodeService, StreamObserver<T>> grpcCall) {
        super(commandNumber);
        this.commandNumber = commandNumber;
        this.nodes = nodes;
        this.currentNodeIndex = initialNodeIndex;
        this.grpcCall = grpcCall;
        this.maxAttempts = nodes.size();
    }

    @Override
    public void onError(Throwable t) {
        if (t instanceof StatusRuntimeException e) {
            Code code = e.getStatus().getCode();
            if (code == Code.UNAVAILABLE || code == Code.DEADLINE_EXCEEDED) {
                attempts++;
                if (attempts < maxAttempts) {
                    System.err.println("[Retry] Async command " + commandNumber + ": Node " + currentNodeIndex + " failed. Retrying on next node...");
                    currentNodeIndex = (currentNodeIndex + 1) % nodes.size();
                    grpcCall.accept(nodes.get(currentNodeIndex), this);
                    return;
                }
            }
        }
        super.onError(t);
    }
}
