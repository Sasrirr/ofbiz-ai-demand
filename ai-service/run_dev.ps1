# AI Demand Service quick run
# Requires: pip install -r requirements.txt

python -c "import uvicorn; uvicorn.run('main:app', host='0.0.0.0', port=8000, reload=True)"