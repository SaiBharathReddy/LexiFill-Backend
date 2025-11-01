## LexiFill - Backend

The LexiFill Backend powers intelligent document parsing and dynamic placeholder filling for legal documents.
It processes uploaded .docx files, uses the Hugging Face Inference API to detect and interpret placeholders, and returns structured data for the frontend to render in a conversational interface.

## Features

- Upload and parse .docx legal documents

- Detect placeholders using Hugging Face Inference API

- Replace placeholder values dynamically

- Serve updated documents for preview and download

- Lightweight RESTful APIs for smooth frontend integration

## Tech Stack

- Spring Boot (Java 17)

- Maven for dependency management

- Apache POI for Word document processing

- Hugging Face Inference API for intelligent placeholder extraction


## Setup Instructions
1. Clone the Repository
git clone https://github.com/SaiBharathReddy/LexiFill-BackEnd.git
cd LexiFill-BackEnd

2. Configure Environment Variables

In src/main/resources/application.properties, set up your configuration:

server.port=8080
huggingface.api.key=your_hugging_face_api_key

3. Build and Run the Application
mvn clean install
mvn spring-boot:run


The backend will run at http://localhost:8080
