/*
 * Copyright (c) 2018-2025 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty5.http.server;

import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.compression.CompressionOptions;
import io.netty5.handler.codec.http.DefaultHttpContent;
import io.netty5.handler.codec.http.DefaultHttpRequest;
import io.netty5.handler.codec.http.FullHttpRequest;
import io.netty5.handler.codec.http.HttpContentCompressor;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import reactor.netty5.http.server.compression.HttpCompressionOptionsSpec;

/**
 * {@link HttpContentCompressor} to enable on-demand compression.
 *
 * @author Stephane Maldini
 */
final class SimpleCompressionHandler extends HttpContentCompressor {

	boolean decoded;
	@Nullable HttpRequest request;

	private SimpleCompressionHandler() {
		super((CompressionOptions[]) null);
	}

	private SimpleCompressionHandler(CompressionOptions... options) {
		super(options);
	}

	static SimpleCompressionHandler create(@Nullable HttpCompressionOptionsSpec compressionOptions) {
		return compressionOptions == null ?
				new SimpleCompressionHandler() :
				new SimpleCompressionHandler(compressionOptions.adapt());
	}

	@Override
	@SuppressWarnings("NullAway")
	public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Buffer buffer) {
			return super.write(ctx, new DefaultHttpContent(buffer));
		}
		if (!decoded && msg instanceof HttpResponse) {
			try {
				// Deliberately suppress "NullAway"
				// This is a lazy initialization
				decode(ctx, request, false);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		if (decoded && request != null && msg instanceof LastHttpContent) {
			decoded = false;
			request = null;
		}
		return super.write(ctx, msg);
	}

	void decode(ChannelHandlerContext ctx, HttpRequest msg, boolean release) throws Exception {
		decoded = true;
		HttpRequest request = msg;
		if (msg instanceof FullHttpRequest fullHttpRequest &&
				(!fullHttpRequest.isAccessible() || fullHttpRequest.payload().readableBytes() == 0)) {
			// 1. This can happen only in HTTP2 use case and delayed response
			// When the incoming FullHttpRequest content is with 0 readableBytes it is released immediately
			// decode(...) will observe a freed content.
			// 2. fireChannelRead(...) is invoked at the end of super.decodeAndClose(...) which will end up
			// in io.netty5.channel.DefaultChannelPipeline.onUnhandledInboundMessage which closes the msg.
			request = new DefaultHttpRequest(msg.protocolVersion(), msg.method(), msg.uri(), msg.headers());
			if (release && fullHttpRequest.isAccessible()) {
				fullHttpRequest.close();
			}
		}
		super.decodeAndClose(ctx, request);
	}

	@Override
	protected void decodeAndClose(ChannelHandlerContext ctx, HttpRequest msg) throws Exception {
		decode(ctx, msg, true);
	}
}
