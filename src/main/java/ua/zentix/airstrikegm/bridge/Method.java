package ua.zentix.airstrikegm.bridge;

import com.google.gson.JsonElement;

import java.util.concurrent.CompletableFuture;

/**
 * Метод моста. Зовётся в потоке HTTP: разбирает параметры там же, а к миру идёт только через поток сервера
 * ({@code GmServer.onMain}) или работой под бюджетом тика. Ошибка разбора — {@link RpcException} сразу, ошибка в потоке
 * сервера — исключением в будущем.
 */
@FunctionalInterface
public interface Method {
    CompletableFuture<JsonElement> call(Args args) throws RpcException;
}
