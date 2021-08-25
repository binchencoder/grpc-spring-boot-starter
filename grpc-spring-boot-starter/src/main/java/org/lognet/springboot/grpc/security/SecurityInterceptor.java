package org.lognet.springboot.grpc.security;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import org.lognet.springboot.grpc.FailureHandlingServerInterceptor;
import org.lognet.springboot.grpc.GRpcErrorHandler;
import org.lognet.springboot.grpc.autoconfigure.GRpcServerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.intercept.AbstractSecurityInterceptor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
public class SecurityInterceptor extends AbstractSecurityInterceptor implements FailureHandlingServerInterceptor, Ordered {


    private final GrpcSecurityMetadataSource securedMethods;

    private final AuthenticationSchemeSelector schemeSelector;

    private GRpcServerProperties.SecurityProperties.Auth authCfg;


    private GRpcErrorHandler errorHandler;

    public SecurityInterceptor(GrpcSecurityMetadataSource securedMethods, AuthenticationSchemeSelector schemeSelector) {
        this.securedMethods = securedMethods;
        this.schemeSelector = schemeSelector;
    }

    @Autowired
    public void setErrorHandler(Optional<GRpcErrorHandler> errorHandler) {
        this.errorHandler = errorHandler.orElseGet(() -> new GRpcErrorHandler() {
        });
    }

    public void setConfig(GRpcServerProperties.SecurityProperties.Auth authCfg) {
        this.authCfg = Optional.ofNullable(authCfg).orElseGet(GRpcServerProperties.SecurityProperties.Auth::new);
    }

    @Override
    public int getOrder() {
        return Optional.ofNullable(authCfg.getInterceptorOrder()).orElse(Ordered.HIGHEST_PRECEDENCE+1);
    }

    @Override
    public Class<?> getSecureObjectClass() {
        return MethodDescriptor.class;
    }

    @Override
    public GrpcSecurityMetadataSource obtainSecurityMetadataSource() {
        return securedMethods;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {


        final CharSequence authorization = Optional.ofNullable(headers.get(Metadata.Key.of("Authorization" + Metadata.BINARY_HEADER_SUFFIX, Metadata.BINARY_BYTE_MARSHALLER)))
                .map(auth -> (CharSequence) StandardCharsets.UTF_8.decode(ByteBuffer.wrap(auth)))
                .orElse(headers.get(Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)));

        try {
            final Context grpcSecurityContext;
            try {
                grpcSecurityContext = setupGRpcSecurityContext(call, authorization);
            } catch (AccessDeniedException e) {
                return fail(next, call, headers, Status.PERMISSION_DENIED, e);
            } catch (Exception e) {
                return fail(next, call, headers, Status.UNAUTHENTICATED, e);
            }
            return Contexts.interceptCall(grpcSecurityContext, call, headers, authenticationPropagatingHandler(next));
        } finally {
            SecurityContextHolder.getContext().setAuthentication(null);
        }


    }
    private <ReqT, RespT> ServerCallHandler<ReqT, RespT> authenticationPropagatingHandler(ServerCallHandler<ReqT, RespT> next) {

        return (call, headers) -> new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(next.startCall(call, headers)) {

            @Override
            public void onMessage(ReqT message) {
                propagateAuthentication(() -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                propagateAuthentication(super::onHalfClose);
            }

            @Override
            public void onCancel() {
                propagateAuthentication(super::onCancel);
            }

            @Override
            public void onComplete() {
                propagateAuthentication(super::onComplete);
            }

            @Override
            public void onReady() {
                propagateAuthentication(super::onReady);
            }

            private void propagateAuthentication(Runnable runnable) {
                try {
                    SecurityContextHolder.getContext().setAuthentication(GrpcSecurity.AUTHENTICATION_CONTEXT_KEY.get());
                    runnable.run();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }

        };

    }

    private Context setupGRpcSecurityContext(ServerCall<?, ?> call, CharSequence authorization) {
        final Authentication authentication = null == authorization ? null :
                schemeSelector.getAuthScheme(authorization)
                        .orElseThrow(() -> new RuntimeException("Can't get authentication from authorization header"));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        beforeInvocation(call.getMethodDescriptor());

        return Context.current()
                .withValue(GrpcSecurity.AUTHENTICATION_CONTEXT_KEY, SecurityContextHolder.getContext().getAuthentication());
    }

    private <RespT, ReqT> ServerCall.Listener<ReqT> fail(ServerCallHandler<ReqT, RespT> next, ServerCall<ReqT, RespT> call, Metadata headers, final Status status, Exception exception) {

        if (authCfg.isFailFast()) {
             closeCall(null, errorHandler, call, headers, status, exception);
             return new ServerCall.Listener<ReqT>() {
                // noop
            };


        } else {
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(next.startCall(call, headers)) {
                @Override
                public void onMessage(ReqT message) {
                    closeCall(message, errorHandler, call, headers, status, exception);
                }
            };

        }

    }


}
