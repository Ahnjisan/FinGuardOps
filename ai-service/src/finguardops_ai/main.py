from fastapi import FastAPI

from finguardops_ai.api.exception_handlers import register_rule_analysis_exception_handlers
from finguardops_ai.api.middleware import RuleAnalysisHttpMiddleware
from finguardops_ai.api.routers.health import router as health_router
from finguardops_ai.api.routers.rule_analysis import router as rule_analysis_router
from finguardops_ai.core.config import get_settings


def create_app() -> FastAPI:
    """Create the FastAPI application without connecting to external systems."""
    settings = get_settings()
    application = FastAPI(title=settings.app_name)
    register_rule_analysis_exception_handlers(application)
    application.add_middleware(
        RuleAnalysisHttpMiddleware,
        path=f"{settings.api_prefix}/v1/rule-analysis",
    )
    application.include_router(health_router, prefix=settings.api_prefix)
    application.include_router(rule_analysis_router, prefix=settings.api_prefix)
    return application


app = create_app()
