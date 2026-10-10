from fastapi import FastAPI

from finguardops_ai.api.exception_handlers import register_rule_analysis_exception_handlers
from finguardops_ai.api.middleware import RuleAnalysisHttpMiddleware
from finguardops_ai.api.routers.ai_report import router as ai_report_router
from finguardops_ai.api.routers.health import router as health_router
from finguardops_ai.api.routers.ml_inference import router as ml_inference_router
from finguardops_ai.api.routers.rule_analysis import router as rule_analysis_router
from finguardops_ai.core.config import get_settings
from finguardops_ai.core.telemetry import TelemetryMiddleware, configure_telemetry


def create_app() -> FastAPI:
    """Create the FastAPI application without connecting to external systems."""
    settings = get_settings()
    application = FastAPI(title=settings.app_name)
    register_rule_analysis_exception_handlers(application)
    application.add_middleware(
        RuleAnalysisHttpMiddleware,
        paths=frozenset(
            {
                f"{settings.api_prefix}/v1/rule-analysis",
                f"{settings.api_prefix}/v2/rule-analysis",
            }
        ),
    )
    application.include_router(health_router, prefix=settings.api_prefix)
    application.include_router(rule_analysis_router, prefix=settings.api_prefix)
    application.include_router(ml_inference_router, prefix=settings.api_prefix)
    application.include_router(ai_report_router, prefix=settings.api_prefix)
    if configure_telemetry():
        application.add_middleware(TelemetryMiddleware)
    return application


app = create_app()
