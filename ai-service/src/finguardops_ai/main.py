from fastapi import FastAPI

from finguardops_ai.api.routers.health import router as health_router
from finguardops_ai.core.config import get_settings


def create_app() -> FastAPI:
    """Create the FastAPI application without connecting to external systems."""
    settings = get_settings()
    application = FastAPI(title=settings.app_name)
    application.include_router(health_router, prefix=settings.api_prefix)
    return application


app = create_app()
