resource "aws_ecr_repository" "projection_registry" {
  name                 = "akka-projection-testing"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = false
  }
}