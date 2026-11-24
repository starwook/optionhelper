package com.optionhelper.watchlist

import com.optionhelper.dashboard.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

data class WatchlistResponse(val symbols: List<String>, val default: String)

@RestController
class WatchlistController {
    @GetMapping("/api/watchlist")
    fun watchlist() = WatchlistResponse(WatchedSymbol.SYMBOLS, WatchedSymbol.DEFAULT.name)
}

/** 모든 API의 `symbol` 파라미터를 한 곳에서 검사한다. 고정 목록 밖 종목은 400. */
@Configuration
class WatchedSymbolCheck : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(
            object : HandlerInterceptor {
                override fun preHandle(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    handler: Any,
                ): Boolean {
                    request.getParameter("symbol")?.let { WatchedSymbol.of(it) }
                    return true
                }
            },
        ).addPathPatterns("/api/**")
    }
}

@RestControllerAdvice
class UnknownSymbolAdvice {
    @ExceptionHandler(UnknownSymbolException::class)
    fun unknown(e: UnknownSymbolException) = ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message ?: "다루지 않는 종목입니다"))
}
